//! Chunk history: remember what a chunk looked like when it was last seen, and
//! query a rectangular region of that memory back.
//!
//! **Full builds only** (Cargo feature `full`). A plain `cargo build` does not
//! compile this file at all, so it will happily stay green over a syntax error
//! here — work on it with `--features full`.
//!
//! The snapshot payload is deliberately **opaque to this crate**: Java produces
//! the bytes and Java consumes them, and Rust only stores, compresses and
//! indexes them by `(dimension, chunkX, chunkZ)`. That keeps every
//! Minecraft-side concept — block-state ids, palette layout, section count,
//! which of those changes between MC versions — on the side of the boundary
//! that already has to track them.
//!
//! Same threading rules as `chatdb.rs`: `&self` everywhere, one writer
//! connection behind a `Mutex` and a reader pool for queries (see `sqlite.rs`),
//! and `query` runs on a worker thread and must not touch the JVM.
//!
//! ## Why this store must be bounded, and how
//!
//! A chat history grows with how much people talk. A chunk history grows with
//! how far the player walks, and a player who explores for a season visits
//! six-figure chunk counts — at a few kilobytes each that is gigabytes in a
//! directory nobody looks at until the disk is full.
//!
//! Two mechanisms, in order of how much they save:
//!
//! 1. **One row per chunk.** `(dimension, chunk_x, chunk_z)` is unique, and a
//!    re-observation overwrites. A player circling their base re-observes the
//!    same chunks thousands of times; an append-only log of that would be
//!    almost entirely duplicates, and "compact it later" is a background job
//!    that would have to run inside a game process.
//! 2. **A row cap with oldest-first eviction** ([`DEFAULT_MAX_ROWS`], settable
//!    per store). Once the store is over the cap it drops the least recently
//!    observed chunks in a batch, leaving 5% headroom so eviction runs about
//!    once per 5% of the cap rather than on every insert past it.
//!
//! Payloads are deflate-compressed on the way in, which is not a substitute for
//! either of the above — it is a constant factor — but chunk data is extremely
//! repetitive and it is a large constant factor.

use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, MutexGuard};

use flate2::Compression;
use flate2::read::DeflateDecoder;
use flate2::write::DeflateEncoder;
use rusqlite::Connection;

use crate::error::{Error, Result};
use crate::jobs::CancelToken;
use crate::payload::{self, PayloadWriter};
use crate::sqlite::{self, ReaderPool};

const SCHEMA_VERSION: i32 = 1;

/// Default cap on stored chunks. At the couple of kilobytes a compressed
/// snapshot runs to, this is a store in the low hundreds of megabytes — large
/// enough that a normal player never reaches it, small enough that a player who
/// does has a bounded file rather than an unbounded one.
pub const DEFAULT_MAX_ROWS: u64 = 100_000;

/// Bounds for [`ChunkStore::set_max_rows`]. The floor exists because a cap of
/// zero or five would make the feature look broken; the ceiling because at some
/// point "configurable" stops meaning "the user knows what they are asking
/// for".
pub const MIN_MAX_ROWS: u64 = 1_000;
pub const MAX_MAX_ROWS: u64 = 10_000_000;

/// A single chunk snapshot larger than this is a bug on the Java side, not a
/// big chunk. Rejecting it names the problem; storing it fills a disk quietly.
const MAX_PAYLOAD_BYTES: usize = 4 * 1024 * 1024;

/// Below this, compression costs more than it saves.
const COMPRESS_THRESHOLD: usize = 64;

const COMPRESSION_NONE: i64 = 0;
const COMPRESSION_DEFLATE: i64 = 1;

/// The largest region `query` will answer, in chunks — 128×128, i.e. a
/// 2048-block square.
///
/// This is a real limit with a real error rather than a silent `LIMIT`, because
/// the failure it prevents is on the Java side: every matched row is copied
/// into one `byte[]`, and a caller who passes `Integer.MIN_VALUE..MAX_VALUE`
/// (which is what "select everything" looks like before anyone thinks about it)
/// would ask for the entire store in a single JVM allocation.
const MAX_QUERY_AREA: i64 = 128 * 128;

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS chunks (
    id          INTEGER PRIMARY KEY,
    dimension   TEXT    NOT NULL,
    chunk_x     INTEGER NOT NULL,
    chunk_z     INTEGER NOT NULL,
    ts_millis   INTEGER NOT NULL,
    compression INTEGER NOT NULL,
    payload     BLOB    NOT NULL
);

-- The identity of a snapshot, and also the index the region query walks:
-- dimension and chunk_x are an equality/range prefix, so a 128-wide region is
-- 128 index seeks rather than a scan of the whole store.
CREATE UNIQUE INDEX IF NOT EXISTS chunks_key ON chunks (dimension, chunk_x, chunk_z);

-- Eviction order. Without it, every eviction sorts the entire table.
CREATE INDEX IF NOT EXISTS chunks_ts ON chunks (ts_millis);
";

const SQL_UPDATE: &str = "UPDATE chunks
                             SET ts_millis = ?4, compression = ?5, payload = ?6
                           WHERE dimension = ?1 AND chunk_x = ?2 AND chunk_z = ?3";

const SQL_INSERT: &str =
    "INSERT INTO chunks (dimension, chunk_x, chunk_z, ts_millis, compression, payload)
                          VALUES (?1, ?2, ?3, ?4, ?5, ?6)";

const SQL_QUERY: &str = "SELECT dimension, chunk_x, chunk_z, ts_millis, compression, payload
                           FROM chunks
                          WHERE dimension = ?1
                            AND chunk_x BETWEEN ?2 AND ?3
                            AND chunk_z BETWEEN ?4 AND ?5
                          ORDER BY chunk_x, chunk_z";

const SQL_EVICT: &str = "DELETE FROM chunks
                          WHERE id IN (SELECT id FROM chunks ORDER BY ts_millis, id LIMIT ?1)";

/// Mirrors the `KIND_CHUNK_QUERY` record layout in `payload.rs`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChunkRecord {
    pub dimension: String,
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub ts_millis: i64,
    pub payload: Vec<u8>,
}

pub struct ChunkStore {
    path: PathBuf,
    writer: Mutex<Connection>,
    readers: ReaderPool,
    /// Exact live row count, which is why `snapshot` does `UPDATE`-then-
    /// `INSERT` instead of a single `INSERT … ON CONFLICT DO UPDATE`: an upsert
    /// reports one changed row either way, so there is no way to tell an
    /// overwrite from a new chunk, and the counter would drift. Two cached
    /// statements are cheaper than the `SELECT COUNT(*)` per insert that
    /// drift-correction would otherwise need.
    rows: AtomicU64,
    max_rows: AtomicU64,
}

impl ChunkStore {
    pub fn open(path: &Path) -> Result<Self> {
        let writer = sqlite::open_writer(path)?;
        let found = sqlite::check_schema_version(&writer, SCHEMA_VERSION, "chunk history")?;
        if found < SCHEMA_VERSION {
            writer.execute_batch(SCHEMA)?;
            sqlite::set_schema_version(&writer, SCHEMA_VERSION)?;
        }
        let rows: i64 = writer.query_row("SELECT COUNT(*) FROM chunks", [], |row| row.get(0))?;

        let store = Self {
            path: path.to_path_buf(),
            writer: Mutex::new(writer),
            readers: ReaderPool::new(path),
            rows: AtomicU64::new(rows.max(0) as u64),
            max_rows: AtomicU64::new(DEFAULT_MAX_ROWS),
        };
        // A store that was written by a build with a larger cap must come back
        // under this one, or lowering the cap would only apply to future rows.
        store.enforce_limit()?;
        Ok(store)
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn max_rows(&self) -> u64 {
        self.max_rows.load(Ordering::Relaxed)
    }

    pub fn row_count(&self) -> u64 {
        self.rows.load(Ordering::Relaxed)
    }

    /// Changes the cap and applies it immediately, clamped to
    /// [`MIN_MAX_ROWS`]..=[`MAX_MAX_ROWS`].
    ///
    /// Clamped rather than rejected: this comes from a settings slider, and the
    /// useful behaviour for "0" is "the smallest we support", not an error
    /// dialog.
    pub fn set_max_rows(&self, max_rows: u64) -> Result<()> {
        self.max_rows.store(
            max_rows.clamp(MIN_MAX_ROWS, MAX_MAX_ROWS),
            Ordering::Relaxed,
        );
        self.enforce_limit()
    }

    /// Poison recovery, matching `chatdb.rs`: a panic that `catch_unwind` already
    /// contained must not disable chunk history for the rest of the session, and
    /// there is no multi-statement invariant a half-finished write could break —
    /// every write here is a single statement in its own implicit transaction.
    /// The row counter is an `AtomicU64` maintained outside this lock and is
    /// re-read from the table on every eviction, so it cannot be left wrong by a
    /// panic either.
    fn writer(&self) -> MutexGuard<'_, Connection> {
        self.writer.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Records one chunk observation, replacing any earlier one for the same
    /// coordinates.
    pub fn snapshot(&self, record: ChunkRecord) -> Result<()> {
        if record.payload.len() > MAX_PAYLOAD_BYTES {
            return Err(Error::new(format!(
                "chunk snapshot payload is {} bytes, over the {MAX_PAYLOAD_BYTES}-byte limit",
                record.payload.len()
            )));
        }
        let (compression, blob) = compress(&record.payload)?;

        let inserted = {
            let conn = self.writer();
            let params = rusqlite::params![
                record.dimension,
                record.chunk_x,
                record.chunk_z,
                record.ts_millis,
                compression,
                blob,
            ];
            let updated = conn.prepare_cached(SQL_UPDATE)?.execute(params)?;
            if updated == 0 {
                conn.prepare_cached(SQL_INSERT)?.execute(params)?;
                true
            } else {
                false
            }
        };

        if inserted {
            let now = self.rows.fetch_add(1, Ordering::Relaxed) + 1;
            if now > self.max_rows.load(Ordering::Relaxed) {
                self.enforce_limit()?;
            }
        }
        Ok(())
    }

    /// Drops the oldest rows until the store is under the cap, with headroom so
    /// this does not run again on the very next insert.
    fn enforce_limit(&self) -> Result<()> {
        let max = self.max_rows.load(Ordering::Relaxed);
        let count = self.rows.load(Ordering::Relaxed);
        if count <= max {
            return Ok(());
        }
        // 5% headroom, at least one row — otherwise a store sitting exactly on
        // the cap would run a DELETE for every single insert forever.
        let target = max.saturating_sub((max / 20).max(1));
        let excess = count - target;

        let conn = self.writer();
        conn.prepare_cached(SQL_EVICT)?
            .execute(rusqlite::params![excess as i64])?;
        // Re-read rather than subtract: `changes()` is the truth about how many
        // rows actually went, and a counter that quietly disagrees with the
        // table is how a cap stops being a cap.
        let actual: i64 = conn.query_row("SELECT COUNT(*) FROM chunks", [], |row| row.get(0))?;
        self.rows.store(actual.max(0) as u64, Ordering::Relaxed);
        Ok(())
    }

    /// Every remembered chunk inside the inclusive chunk-coordinate rectangle.
    ///
    /// Bounds arrive already normalised (`min <= max`) — `ffi.rs` swaps them if
    /// Java passes them the other way round.
    pub fn query(
        &self,
        dimension: &str,
        min_x: i32,
        min_z: i32,
        max_x: i32,
        max_z: i32,
        cancel: &CancelToken,
    ) -> Result<Vec<ChunkRecord>> {
        if cancel.is_cancelled() {
            return Ok(Vec::new());
        }
        // i64 throughout: `max_x - min_x` overflows i32 for the full coordinate
        // range, which is exactly the argument a caller passes when they mean
        // "everything".
        let width = i64::from(max_x) - i64::from(min_x) + 1;
        let height = i64::from(max_z) - i64::from(min_z) + 1;
        let area = width.saturating_mul(height);
        if area > MAX_QUERY_AREA {
            return Err(Error::new(format!(
                "chunk query covers {area} chunks, over the {MAX_QUERY_AREA}-chunk limit; ask for a smaller region"
            )));
        }

        let out = self.readers.with(cancel, |conn| {
            let mut stmt = conn.prepare_cached(SQL_QUERY)?;
            let mut rows = stmt.query(rusqlite::params![dimension, min_x, max_x, min_z, max_z])?;
            let mut out = Vec::new();
            while let Some(row) = rows.next()? {
                if cancel.is_cancelled() {
                    return Ok(Vec::new());
                }
                let compression: i64 = row.get(4)?;
                let blob: Vec<u8> = row.get(5)?;
                // A payload that will not decompress means the file is damaged.
                // Skipping the row keeps the rest of the region usable, which
                // beats failing a whole query — and beats handing Java bytes it
                // would then try to parse as a chunk.
                let Ok(payload) = decompress(compression, blob) else {
                    continue;
                };
                out.push(ChunkRecord {
                    dimension: row.get(0)?,
                    chunk_x: row.get(1)?,
                    chunk_z: row.get(2)?,
                    ts_millis: row.get(3)?,
                    payload,
                });
            }
            Ok(out)
        })?;
        // `None` is a cancelled statement, not a failure — see `ReaderPool::with`.
        Ok(out.unwrap_or_default())
    }
}

/// Deflate, unless it does not pay.
///
/// `Compression::fast()` rather than the default level: this runs on whatever
/// thread observed the chunk, chunk data is repetitive enough that level 1 gets
/// most of the win, and a snapshot that costs a millisecond of the client tick
/// is a snapshot that gets noticed.
///
/// Storing the result only when it is actually smaller matters more than it
/// looks: a Java side that decides to pre-compress its own payload would
/// otherwise pay for a second, useless deflate pass that makes the data
/// slightly *larger* on every chunk it ever sees.
fn compress(raw: &[u8]) -> Result<(i64, Vec<u8>)> {
    if raw.len() < COMPRESS_THRESHOLD {
        return Ok((COMPRESSION_NONE, raw.to_vec()));
    }
    let mut encoder = DeflateEncoder::new(Vec::with_capacity(raw.len() / 2), Compression::fast());
    encoder.write_all(raw)?;
    let packed = encoder.finish()?;
    if packed.len() < raw.len() {
        Ok((COMPRESSION_DEFLATE, packed))
    } else {
        Ok((COMPRESSION_NONE, raw.to_vec()))
    }
}

fn decompress(compression: i64, blob: Vec<u8>) -> Result<Vec<u8>> {
    match compression {
        COMPRESSION_NONE => Ok(blob),
        COMPRESSION_DEFLATE => {
            let mut out = Vec::with_capacity(blob.len() * 3);
            // Bounded read: the blob comes off disk, and a corrupted or crafted
            // deflate stream expands without limit. `MAX_PAYLOAD_BYTES + 1` so
            // an over-long stream is detectable rather than silently truncated.
            DeflateDecoder::new(blob.as_slice())
                .take(MAX_PAYLOAD_BYTES as u64 + 1)
                .read_to_end(&mut out)?;
            if out.len() > MAX_PAYLOAD_BYTES {
                return Err(Error::new(
                    "stored chunk snapshot decompresses past the payload limit; treating it as damaged",
                ));
            }
            Ok(out)
        }
        other => Err(Error::new(format!(
            "stored chunk snapshot uses unknown compression {other}"
        ))),
    }
}

/// Encodes query results into the `jobTake` wire format.
pub fn encode_query_results(records: &[ChunkRecord]) -> Vec<u8> {
    if records.is_empty() {
        return payload::empty(payload::KIND_CHUNK_QUERY);
    }
    let mut w = PayloadWriter::new(payload::KIND_CHUNK_QUERY);
    for r in records {
        w.begin_record();
        w.put_str(&r.dimension);
        w.put_i32(r.chunk_x);
        w.put_i32(r.chunk_z);
        w.put_i64(r.ts_millis);
        w.put_bytes(&r.payload);
    }
    w.finish()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sqlite::testutil::TempDir;

    const OVERWORLD: &str = "minecraft:overworld";

    fn store(dir: &TempDir) -> ChunkStore {
        ChunkStore::open(&dir.file("chunk_history.db")).expect("open")
    }

    fn snap(store: &ChunkStore, x: i32, z: i32, ts: i64, payload: Vec<u8>) {
        store
            .snapshot(ChunkRecord {
                dimension: OVERWORLD.into(),
                chunk_x: x,
                chunk_z: z,
                ts_millis: ts,
                payload,
            })
            .expect("snapshot");
    }

    /// The single chunk at `(x, z)` — a 1×1 region, matching how `snap` above is
    /// addressed. The argument order is `(min_x, min_z, max_x, max_z)`, not
    /// `(min_x, max_x, min_z, max_z)`; getting that backwards builds an inverted
    /// rectangle that quietly matches nothing, which is what four of these tests
    /// were asserting against before.
    fn query(store: &ChunkStore, x: i32, z: i32) -> Vec<ChunkRecord> {
        store
            .query(OVERWORLD, x, z, x, z, &CancelToken::never())
            .expect("query")
    }

    #[test]
    fn a_snapshot_round_trips_byte_for_byte() {
        let dir = TempDir::new("chunks-roundtrip");
        let store = store(&dir);
        // Repetitive, like real chunk data — this one compresses.
        let payload: Vec<u8> = (0..4096u32).map(|i| (i % 7) as u8).collect();
        snap(&store, -3, 7, 42, payload.clone());

        let rows = store
            .query(OVERWORLD, -4, 6, -2, 8, &CancelToken::never())
            .expect("query");
        assert_eq!(rows.len(), 1);
        assert_eq!(
            rows[0].payload, payload,
            "the opaque blob must survive exactly"
        );
        assert_eq!(
            (rows[0].chunk_x, rows[0].chunk_z, rows[0].ts_millis),
            (-3, 7, 42)
        );
    }

    #[test]
    fn incompressible_payloads_are_stored_raw_and_still_round_trip() {
        let dir = TempDir::new("chunks-incompressible");
        let store = store(&dir);
        // A xorshift PRNG rather than a crate: deflate must not find structure
        // in it, and that is the only property this needs.
        let mut s: u64 = 0x2545_F491_4F6C_DD1D;
        let noise: Vec<u8> = (0..8192)
            .map(|_| {
                s ^= s << 13;
                s ^= s >> 7;
                s ^= s << 17;
                s as u8
            })
            .collect();
        snap(&store, 0, 0, 1, noise.clone());
        snap(&store, 1, 0, 1, vec![0u8; 8]); // under the compress threshold
        assert_eq!(query(&store, 0, 0)[0].payload, noise);
        assert_eq!(query(&store, 1, 0)[0].payload, vec![0u8; 8]);
    }

    #[test]
    fn re_observing_a_chunk_replaces_it_instead_of_appending() {
        let dir = TempDir::new("chunks-upsert");
        let store = store(&dir);
        snap(
            &store,
            5,
            5,
            100,
            b"old chunk contents, padded out a bit".to_vec(),
        );
        snap(
            &store,
            5,
            5,
            200,
            b"new chunk contents, padded out a bit".to_vec(),
        );

        let rows = query(&store, 5, 5);
        assert_eq!(rows.len(), 1, "one row per chunk, always");
        assert_eq!(rows[0].ts_millis, 200);
        assert_eq!(rows[0].payload, b"new chunk contents, padded out a bit");
        assert_eq!(
            store.row_count(),
            1,
            "the counter must not drift on overwrite"
        );
    }

    #[test]
    fn dimensions_do_not_leak_into_each_other() {
        let dir = TempDir::new("chunks-dimension");
        let store = store(&dir);
        snap(&store, 0, 0, 1, b"overworld".to_vec());
        store
            .snapshot(ChunkRecord {
                dimension: "minecraft:the_nether".into(),
                chunk_x: 0,
                chunk_z: 0,
                ts_millis: 2,
                payload: b"nether".to_vec(),
            })
            .expect("snapshot");

        assert_eq!(query(&store, 0, 0)[0].payload, b"overworld");
        let nether = store
            .query("minecraft:the_nether", 0, 0, 0, 0, &CancelToken::never())
            .expect("query");
        assert_eq!(nether[0].payload, b"nether");
        assert_eq!(store.row_count(), 2, "same coordinates, different rows");
    }

    #[test]
    fn a_region_query_returns_only_what_is_inside_it() {
        let dir = TempDir::new("chunks-region");
        let store = store(&dir);
        for x in -5..=5 {
            for z in -5..=5 {
                snap(&store, x, z, 1, format!("chunk {x},{z}").into_bytes());
            }
        }
        let rows = store
            .query(OVERWORLD, -1, -1, 1, 1, &CancelToken::never())
            .expect("query");
        assert_eq!(rows.len(), 9);
        assert!(
            rows.iter()
                .all(|r| (-1..=1).contains(&r.chunk_x) && (-1..=1).contains(&r.chunk_z))
        );
        // Ordering is deterministic, which is what makes a map overlay stable.
        assert_eq!((rows[0].chunk_x, rows[0].chunk_z), (-1, -1));
        assert_eq!((rows[8].chunk_x, rows[8].chunk_z), (1, 1));
    }

    #[test]
    fn an_absurd_region_is_refused_rather_than_answered() {
        let dir = TempDir::new("chunks-area");
        let store = store(&dir);
        // What "select everything" looks like before anyone thinks about it.
        // Note this also overflows i32 subtraction, which is the other half of
        // the reason for the check.
        let err = store
            .query(
                OVERWORLD,
                i32::MIN,
                i32::MIN,
                i32::MAX,
                i32::MAX,
                &CancelToken::never(),
            )
            .expect_err("must be refused");
        assert!(err.message().contains("smaller region"), "{err}");
        // The largest allowed region is still allowed.
        assert!(
            store
                .query(OVERWORLD, 0, 0, 127, 127, &CancelToken::never())
                .is_ok()
        );
    }

    #[test]
    fn eviction_drops_the_oldest_and_keeps_the_store_under_the_cap() {
        let dir = TempDir::new("chunks-evict");
        let store = store(&dir);
        store.set_max_rows(0).expect("clamped to the floor");
        assert_eq!(store.max_rows(), MIN_MAX_ROWS);

        // Fill past the cap. `ts_millis` ascends with x, so eviction order is
        // known exactly.
        let over = MIN_MAX_ROWS + 250;
        for i in 0..over {
            snap(&store, i as i32, 0, i as i64, vec![7u8; 128]);
        }

        assert!(
            store.row_count() <= MIN_MAX_ROWS,
            "still {} rows against a cap of {MIN_MAX_ROWS}",
            store.row_count()
        );
        // The newest chunk is there and the oldest is gone.
        assert_eq!(query(&store, (over - 1) as i32, 0).len(), 1);
        assert_eq!(
            query(&store, 0, 0).len(),
            0,
            "oldest must have been evicted"
        );

        // And the count agrees with the table after all that.
        let counted: i64 = store
            .writer()
            .query_row("SELECT COUNT(*) FROM chunks", [], |r| r.get(0))
            .unwrap();
        assert_eq!(counted as u64, store.row_count());
    }

    #[test]
    fn lowering_the_cap_takes_effect_immediately() {
        let dir = TempDir::new("chunks-cap-lower");
        let path = dir.file("chunk_history.db");
        {
            let store = ChunkStore::open(&path).expect("open");
            for i in 0..2_000 {
                snap(&store, i, 0, i as i64, vec![1u8; 128]);
            }
            assert_eq!(store.row_count(), 2_000);
            store.set_max_rows(MIN_MAX_ROWS).expect("lower");
            assert!(store.row_count() <= MIN_MAX_ROWS);
        }
        // ...and survives a reopen, which is where a cap applied only to new
        // rows would show up.
        let store = ChunkStore::open(&path).expect("reopen");
        assert!(store.row_count() <= MIN_MAX_ROWS);
        assert_eq!(
            store.max_rows(),
            DEFAULT_MAX_ROWS,
            "the cap itself is not persisted"
        );
    }

    #[test]
    fn an_oversized_payload_is_named_rather_than_stored() {
        let dir = TempDir::new("chunks-payload-cap");
        let store = store(&dir);
        let err = store
            .snapshot(ChunkRecord {
                dimension: OVERWORLD.into(),
                chunk_x: 0,
                chunk_z: 0,
                ts_millis: 1,
                payload: vec![0u8; MAX_PAYLOAD_BYTES + 1],
            })
            .expect_err("must be refused");
        assert!(err.message().contains("over the"), "{err}");
        assert_eq!(store.row_count(), 0);
    }

    #[test]
    fn an_already_cancelled_query_does_no_work() {
        let dir = TempDir::new("chunks-cancel");
        let store = store(&dir);
        snap(&store, 0, 0, 1, vec![3u8; 256]);
        assert!(
            store
                .query(OVERWORLD, -1, -1, 1, 1, &CancelToken::cancelled())
                .expect("cancelled is not an error")
                .is_empty()
        );
    }

    #[test]
    fn a_damaged_row_costs_that_row_and_not_the_query() {
        let dir = TempDir::new("chunks-damaged");
        let store = store(&dir);
        snap(&store, 0, 0, 1, vec![9u8; 4096]);
        snap(&store, 1, 0, 1, vec![8u8; 4096]);
        // Corrupt one payload behind the store's back, the way a bad sector or
        // a half-written file would.
        store
            .writer()
            .execute(
                "UPDATE chunks SET payload = X'deadbeef' WHERE chunk_x = 0",
                [],
            )
            .expect("corrupt");

        let rows = store
            .query(OVERWORLD, 0, 0, 1, 0, &CancelToken::never())
            .expect("query must still succeed");
        assert_eq!(rows.len(), 1, "the readable chunk still comes back");
        assert_eq!(rows[0].chunk_x, 1);
    }

    #[test]
    fn reopening_keeps_the_history() {
        let dir = TempDir::new("chunks-reopen");
        let path = dir.file("chunk_history.db");
        {
            let store = ChunkStore::open(&path).expect("open");
            snap(&store, 12, -34, 56, vec![4u8; 1024]);
        }
        let store = ChunkStore::open(&path).expect("reopen");
        assert_eq!(store.row_count(), 1);
        let rows = query(&store, 12, -34);
        assert_eq!(rows[0].payload, vec![4u8; 1024]);
    }

    #[test]
    fn a_newer_schema_is_refused_rather_than_misread() {
        let dir = TempDir::new("chunks-schema");
        let path = dir.file("chunk_history.db");
        {
            let store = ChunkStore::open(&path).expect("open");
            crate::sqlite::set_schema_version(&store.writer(), SCHEMA_VERSION + 5).expect("bump");
        }
        let err = match ChunkStore::open(&path) {
            Err(e) => e,
            Ok(_) => panic!("a store from a newer schema must be refused"),
        };
        assert!(err.message().contains("newer build"), "{}", err.message());
    }

    #[test]
    fn snapshots_and_queries_run_concurrently() {
        use std::sync::Arc;
        let dir = TempDir::new("chunks-concurrent");
        let store = Arc::new(store(&dir));
        for x in 0..64 {
            snap(&store, x, 0, 1, vec![2u8; 512]);
        }

        let writer = {
            let store = Arc::clone(&store);
            std::thread::spawn(move || {
                for x in 64..192 {
                    snap(&store, x, 1, 2, vec![3u8; 512]);
                }
            })
        };
        let reader = {
            let store = Arc::clone(&store);
            std::thread::spawn(move || {
                for _ in 0..50 {
                    store
                        .query(OVERWORLD, 0, 0, 63, 0, &CancelToken::never())
                        .expect("query during writes");
                }
            })
        };
        writer.join().expect("writer");
        reader.join().expect("reader");
        assert_eq!(store.row_count(), 192);
    }

    #[test]
    fn encoding_carries_the_opaque_payload_verbatim() {
        let out = encode_query_results(&[ChunkRecord {
            dimension: OVERWORLD.into(),
            chunk_x: -3,
            chunk_z: 7,
            ts_millis: 42,
            payload: vec![0xde, 0xad],
        }]);
        assert_eq!(out[4], payload::KIND_CHUNK_QUERY);
        assert_eq!(u32::from_be_bytes(out[5..9].try_into().unwrap()), 1);
        // The blob must survive byte-for-byte; a chunk snapshot Java can't
        // decode back is worse than no snapshot.
        assert_eq!(&out[out.len() - 2..], &[0xde, 0xad]);
    }
}
