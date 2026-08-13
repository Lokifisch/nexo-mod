//! Shared SQLite plumbing for the two stores in this crate (`chatdb`, and
//! `chunks` in full builds).
//!
//! Both stores have the same shape and the same problem, so the answer lives
//! here once rather than twice.
//!
//! ## The problem: `Connection` is `Send` but not `Sync`
//!
//! The registry hands Java an `Arc<ChatDb>` and two Java threads legitimately
//! hold it at the same time — the client tick thread appending a chat line
//! while a job-pool worker searches. A single `Mutex<Connection>` would make
//! that correct and also make it wrong in a way players would feel: every
//! insert would queue behind a search that can take a second over a season's
//! history, and those inserts happen on the tick thread.
//!
//! So each store keeps **one writer connection behind a `Mutex`** (writes are
//! serialised anyway — SQLite allows exactly one writer) and a **small pool of
//! reader connections** ([`ReaderPool`]) that queries check out. In WAL mode
//! readers do not block the writer and the writer does not block readers, so
//! the two sides genuinely run at once instead of merely appearing to.
//!
//! A full connection pool (r2d2 and friends) would be the usual answer; it is
//! not worth a dependency here. There are at most three job-pool workers (see
//! `jobs.rs`), so the pool's job is only to avoid re-opening a connection per
//! query, and two idle readers cover that.
//!
//! ## Cancellation
//!
//! Polling [`CancelToken`] between rows is not enough on its own. Both real
//! queries sort or scan *before* the first row is stepped, so a cancelled
//! search would still pay for the whole thing and only then notice. SQLite's
//! progress handler fixes that: it runs every `PROGRESS_OPS` VM instructions
//! and aborts the statement the moment it returns `true`. [`ReaderPool::with`]
//! installs one for the duration of the query and removes it afterwards —
//! removing it matters, because the connection goes back in the pool and a
//! stale handler would carry a dead query's cancel token into the next one.

use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};
use std::time::Duration;

use rusqlite::{Connection, ErrorCode, OpenFlags};

use crate::error::{Error, Result};
use crate::jobs::CancelToken;

/// How long a statement waits for a competing writer before giving up. WAL
/// makes contention rare (readers never block), so this only ever covers a
/// checkpoint or a second process holding the file — neither of which should
/// surface to the player as an error if simply waiting would do.
const BUSY_TIMEOUT: Duration = Duration::from_secs(5);

/// SQLite VM instructions between progress-handler calls. Small enough that a
/// cancel is honoured in well under a frame, large enough that the callback
/// itself (a relaxed atomic load) is noise.
const PROGRESS_OPS: i32 = 20_000;

/// Readers kept warm between queries. There are at most three job-pool workers
/// and queries are not submitted in bursts, so anything larger would just be
/// idle file descriptors.
const MAX_IDLE_READERS: usize = 2;

/// Pragmas every connection in this crate gets.
///
/// * `synchronous=NORMAL` is the one that matters for the tick thread: in WAL
///   mode it means a commit does **not** fsync, only a checkpoint does. The
///   documented worst case is losing the last few commits if the *machine*
///   loses power (a process crash — including a Minecraft crash — cannot lose
///   them), which is the right trade for a chat log.
/// * `temp_store=MEMORY` keeps the sorter for `ORDER BY ts DESC` off disk.
/// * `foreign_keys` is left at its default: neither schema uses them, and
///   turning it on would only cost a check per write.
const COMMON_PRAGMAS: &str = "
    PRAGMA journal_mode = WAL;
    PRAGMA synchronous = NORMAL;
    PRAGMA temp_store = MEMORY;
";

/// Deliberately **not** `OpenFlags::default()`, which includes
/// `SQLITE_OPEN_URI`. With URI parsing on, a perfectly ordinary Windows path
/// containing `?` or `#` would be reinterpreted as a URI with query parameters
/// — a corruption of the caller's path that only shows up on someone else's
/// machine.
fn flags(create: bool) -> OpenFlags {
    let mut f = OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    if create {
        f |= OpenFlags::SQLITE_OPEN_CREATE;
    }
    f
}

fn configure(conn: &Connection) -> Result<()> {
    conn.busy_timeout(BUSY_TIMEOUT)?;
    // `execute_batch` rather than `pragma_update`: `PRAGMA journal_mode` returns
    // a row, and `pragma_update` goes through `execute`, which rejects a
    // statement that produced results.
    conn.execute_batch(COMMON_PRAGMAS)?;
    Ok(())
}

/// Creates missing parent directories, then opens the writer connection.
///
/// The directory creation is here rather than in Java because the JVM side
/// passes a path under the mod's config directory that does not exist on a
/// fresh install, and failing `chatDbOpen` with "no such file" would read as a
/// bug in the caller.
pub fn open_writer(path: &Path) -> Result<Connection> {
    if let Some(parent) = path.parent()
        && !parent.as_os_str().is_empty()
    {
        std::fs::create_dir_all(parent)?;
    }
    let conn = Connection::open_with_flags(path, flags(true))?;
    configure(&conn)?;
    Ok(conn)
}

/// Readers are opened read-**write** on purpose. A `SQLITE_OPEN_READ_ONLY`
/// connection to a WAL database still needs to write the `-shm` index, so it
/// only works when some other connection in the same process already holds the
/// database open — which is true here today and would be a very confusing
/// failure the first time it stopped being true.
fn open_reader(path: &Path) -> Result<Connection> {
    let conn = Connection::open_with_flags(path, flags(false))?;
    configure(&conn)?;
    Ok(conn)
}

/// True for the error SQLite reports when the progress handler aborted a
/// statement. Callers turn it into "the caller stopped caring", not a failure:
/// the job pool already marks a cancelled job `CANCELLED` regardless of what
/// its closure returned, so an empty `Ok` is both honest and quiet.
pub fn is_interrupt(e: &rusqlite::Error) -> bool {
    matches!(e, rusqlite::Error::SqliteFailure(f, _) if f.code == ErrorCode::OperationInterrupted)
}

/// Removes the progress handler however the query exits — including via `?` and
/// including via a panic that `jobs.rs` catches.
struct ProgressGuard<'a>(&'a Connection);

impl Drop for ProgressGuard<'_> {
    fn drop(&mut self) {
        // `num_ops < 1` disables the handler. Failure here is unreachable in
        // practice and there is nowhere to report it from a `Drop`; the
        // connection is dropped rather than pooled if this somehow mattered.
        let _ = self.0.progress_handler(0, None::<fn() -> bool>);
    }
}

/// A small set of connections used only for reads.
pub struct ReaderPool {
    path: PathBuf,
    idle: Mutex<Vec<Connection>>,
}

impl ReaderPool {
    pub fn new(path: &Path) -> Self {
        Self {
            path: path.to_path_buf(),
            idle: Mutex::new(Vec::new()),
        }
    }

    /// Lock poisoning is recovered from for the same reason as in `registry.rs`:
    /// the guarded value is a plain `Vec` with no invariant a panic can
    /// half-break, and honouring the poison would disable search for the rest
    /// of the session over a bug `catch_unwind` already contained.
    fn lock(&self) -> MutexGuard<'_, Vec<Connection>> {
        self.idle.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Runs `f` on a pooled reader with `cancel` wired into SQLite's progress
    /// handler.
    ///
    /// `Ok(None)` means the progress handler aborted the statement — i.e. the
    /// caller cancelled. That is deliberately not an `Err`: the job pool marks
    /// a cancelled job `CANCELLED` on its own, and reporting a failure as well
    /// would put a scary line in `nativeLastError()` for something the player
    /// did on purpose. Callers turn it into an empty result.
    ///
    /// The connection is returned to the pool only on a clean exit or a cancel.
    /// One that errored is dropped instead — it may be mid-transaction or in
    /// some state this code did not anticipate, and re-opening is cheap next to
    /// reasoning about that.
    pub fn with<T>(
        &self,
        cancel: &CancelToken,
        f: impl FnOnce(&Connection) -> rusqlite::Result<T>,
    ) -> Result<Option<T>> {
        let conn = match self.lock().pop() {
            Some(conn) => conn,
            None => open_reader(&self.path)?,
        };

        let result = {
            let token = cancel.clone();
            conn.progress_handler(PROGRESS_OPS, Some(move || token.is_cancelled()))?;
            let _guard = ProgressGuard(&conn);
            f(&conn)
        };

        let out = match result {
            Ok(value) => Ok(Some(value)),
            Err(e) if is_interrupt(&e) => Ok(None),
            Err(e) => Err(Error::from(e)),
        };
        if out.is_ok() {
            let mut idle = self.lock();
            if idle.len() < MAX_IDLE_READERS {
                idle.push(conn);
            }
        }
        out
    }
}

/// `PRAGMA user_version`, the standard place to keep a schema version. It costs
/// nothing (it lives in the database header) and it is the difference between
/// "this file was written by a newer Nexo" and a stream of "no such column"
/// errors.
pub fn check_schema_version(conn: &Connection, current: i32, what: &str) -> Result<i32> {
    let found: i32 = conn.query_row("PRAGMA user_version", [], |row| row.get(0))?;
    if found > current {
        return Err(Error::new(format!(
            "{what} at schema version {found} was written by a newer build of Nexo Mod (this one understands {current}); refusing to touch it"
        )));
    }
    Ok(found)
}

pub fn set_schema_version(conn: &Connection, version: i32) -> Result<()> {
    // Not a bound parameter: SQLite does not allow parameters in PRAGMA, and
    // `version` is a crate constant rather than anything a caller supplies.
    conn.execute_batch(&format!("PRAGMA user_version = {version}"))?;
    Ok(())
}

#[cfg(test)]
pub(crate) mod testutil {
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    /// A unique directory under the system temp dir, removed on drop.
    ///
    /// A `tempfile` dev-dependency would do this properly, but every store test
    /// needs exactly one directory and nothing else, and this crate ships
    /// inside a mod jar — keeping the dependency list short is worth twenty
    /// lines.
    pub struct TempDir(PathBuf);

    static SEQ: AtomicU64 = AtomicU64::new(0);

    impl TempDir {
        pub fn new(tag: &str) -> Self {
            let n = SEQ.fetch_add(1, Ordering::Relaxed);
            let dir = std::env::temp_dir().join(format!(
                "nexo-core-test-{}-{}-{}",
                tag,
                std::process::id(),
                n
            ));
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).expect("create temp dir");
            Self(dir)
        }

        pub fn file(&self, name: &str) -> PathBuf {
            self.0.join(name)
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
}
