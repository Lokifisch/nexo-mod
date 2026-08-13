//! Fire-and-poll job pool for anything that must not run on a Minecraft thread.
//!
//! A chat-history search over a season's worth of messages, or a chunk-history
//! query over a few hundred chunks, takes long enough that running it inside a
//! native call would stall whichever thread called it — and the callers are the
//! render thread (a search box) and the client tick thread. So the FFI splits
//! it: `…Async` returns a `jobId` immediately, Java polls `jobIsReady` on its
//! own schedule, and `jobTake` collects the bytes.
//!
//! **Worker threads never touch the JVM.** No `JNIEnv`, no `JavaVM::attach`, no
//! callbacks into Java. That is a deliberate restriction, not an oversight:
//! attaching native threads to the JVM means every one of them must detach
//! before exit or the JVM leaks (and refuses to shut down), and a callback into
//! Java from a pool thread would run mod code on a thread Minecraft knows
//! nothing about. Polling is duller and cannot deadlock the game.
//!
//! Agent 3: your job closures are therefore pure Rust. If you find yourself
//! wanting a `JNIEnv` inside one, the answer is to put the data in the payload
//! and let Java do that part.

use std::any::Any;
use std::panic::{self, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant};

use crate::error::{Error, Result, describe_panic};
use crate::registry::Registry;

/// Mirrors `NexoNative.JOB_*` on the Java side. Values are part of the FFI
/// contract — append only, never renumber.
pub const STATUS_UNKNOWN: i32 = 0;
pub const STATUS_PENDING: i32 = 1;
pub const STATUS_READY: i32 = 2;
pub const STATUS_FAILED: i32 = 3;
pub const STATUS_CANCELLED: i32 = 4;

/// A settled result nobody collected within this window is dropped.
///
/// Without it, a screen that fires a search per keystroke and gets closed
/// before the results arrive leaks every one of them for the rest of the
/// session. Five minutes is far beyond any legitimate poll interval (Java polls
/// per tick) while still being short enough that the leak is bounded.
const RESULT_TTL: Duration = Duration::from_secs(300);

/// Backpressure. Hit only by a caller submitting in a loop without collecting —
/// which is a bug, and one that is much easier to find as "too many in-flight
/// native jobs" than as steadily growing RSS.
const MAX_LIVE_JOBS: usize = 256;

/// Cooperative cancellation. There is no way to kill a running thread in safe
/// Rust, so cancellation is advisory: the token flips and the job is expected to
/// notice.
///
/// Agent 3: check this inside every loop that can run long — per row scanned,
/// per chunk visited. A job that never checks still *completes*, it just wastes
/// the work.
#[derive(Clone)]
pub struct CancelToken {
    flag: Arc<AtomicBool>,
}

impl CancelToken {
    pub fn is_cancelled(&self) -> bool {
        self.flag.load(Ordering::Relaxed)
    }

    /// A token nothing can ever flip.
    ///
    /// For call paths that are synchronous and therefore have no cancellation
    /// story — and for tests, which need to call a job body directly without
    /// standing up a pool. Kept out of `#[cfg(test)]` because "this work is not
    /// cancellable" is a real thing for a caller to say, and saying it
    /// explicitly beats threading an `Option<&CancelToken>` through the stores.
    pub fn never() -> Self {
        Self {
            flag: Arc::new(AtomicBool::new(false)),
        }
    }

    /// A token that is already cancelled, for testing the bail-out paths.
    #[cfg(test)]
    pub fn cancelled() -> Self {
        Self {
            flag: Arc::new(AtomicBool::new(true)),
        }
    }
}

enum JobState {
    Pending,
    Ready(Vec<u8>),
    Failed(String),
    Cancelled,
}

struct JobInner {
    state: JobState,
    /// When the job stopped being `Pending`; `None` while it still is. Drives
    /// the TTL reaper — a pending job is never reaped, however long it runs.
    settled_at: Option<Instant>,
}

struct Job {
    inner: Mutex<JobInner>,
    cancel: Arc<AtomicBool>,
}

impl Job {
    fn new(cancel: Arc<AtomicBool>) -> Self {
        Self {
            inner: Mutex::new(JobInner {
                state: JobState::Pending,
                settled_at: None,
            }),
            cancel,
        }
    }

    /// Poison recovery for the same reason as `Registry`: the guarded state is
    /// a plain enum with no invariant a panic can half-break, and a poisoned
    /// job would be stuck `Pending` forever, which is exactly the hang the
    /// whole polling design exists to avoid.
    fn lock(&self) -> MutexGuard<'_, JobInner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// First writer wins. That is what makes `cancel` racing a finishing worker
    /// safe: whichever gets there first decides, and the loser is dropped
    /// instead of resurrecting a job Java has already been told is cancelled.
    fn settle(&self, state: JobState) {
        let mut guard = self.lock();
        if matches!(guard.state, JobState::Pending) {
            guard.state = state;
            guard.settled_at = Some(Instant::now());
        }
    }

    fn status(&self) -> i32 {
        match self.lock().state {
            JobState::Pending => STATUS_PENDING,
            JobState::Ready(_) => STATUS_READY,
            JobState::Failed(_) => STATUS_FAILED,
            JobState::Cancelled => STATUS_CANCELLED,
        }
    }
}

type Work = Box<dyn FnOnce() + Send + 'static>;

pub struct JobPool {
    jobs: Registry<Job>,
    /// `None` until the first submit and again after `shutdown`. Dropping the
    /// sender is what tells the workers to exit — there is no separate stop
    /// flag to get out of sync with.
    sender: Mutex<Option<Sender<Work>>>,
}

/// Process-wide, created on first use. Nothing is spawned until a job is
/// actually submitted, so a session that never opens the chat search never pays
/// for a thread.
pub fn pool() -> &'static JobPool {
    static POOL: OnceLock<JobPool> = OnceLock::new();
    POOL.get_or_init(JobPool::new)
}

impl JobPool {
    fn new() -> Self {
        Self {
            jobs: Registry::new(),
            sender: Mutex::new(None),
        }
    }

    /// Deliberately small. These are background conveniences competing with a
    /// game that wants every core it can get for rendering and chunk meshing;
    /// a search finishing in 300 ms instead of 200 ms is invisible, a dropped
    /// frame is not.
    fn worker_count() -> usize {
        std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(2)
            .saturating_sub(1)
            .clamp(1, 3)
    }

    fn ensure_started(&self) -> Sender<Work> {
        let mut guard = self.sender.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(tx) = guard.as_ref() {
            return tx.clone();
        }

        let (tx, rx) = channel::<Work>();
        // One `Receiver` behind a mutex is the classic std-only pool: each
        // worker locks only long enough to pull the next closure, so the lock
        // is never held while a job runs.
        let rx = Arc::new(Mutex::new(rx));
        for i in 0..Self::worker_count() {
            let rx: Arc<Mutex<Receiver<Work>>> = Arc::clone(&rx);
            let spawned = std::thread::Builder::new()
                // Named so a profiler or a thread dump in a crash report points
                // at this crate instead of an anonymous "Thread-17".
                .name(format!("nexo-native-job-{i}"))
                .spawn(move || {
                    loop {
                        let work = {
                            let guard = rx.lock().unwrap_or_else(|e| e.into_inner());
                            guard.recv()
                        };
                        match work {
                            Ok(work) => work(),
                            // Sender dropped: shutdown, or the pool was never
                            // used again. Either way there is no more work.
                            Err(_) => break,
                        }
                    }
                });
            if spawned.is_err() {
                // Out of OS threads. Whatever workers did start still drain the
                // queue; failing the whole pool here would take down features
                // that would otherwise just be slow.
                break;
            }
        }

        *guard = Some(tx.clone());
        tx
    }

    /// Queues `f` and returns its job id, or `Err` if the pool is saturated.
    ///
    /// `f` receives the cancel token so it can bail out early; returning
    /// `Err` from it surfaces to Java as a failed job whose message comes back
    /// through `nativeLastError()` on the `jobTake` call.
    pub fn submit<F>(&self, f: F) -> Result<i64>
    where
        F: FnOnce(&CancelToken) -> Result<Vec<u8>> + Send + 'static,
    {
        self.reap_stale();
        if self.jobs.len() >= MAX_LIVE_JOBS {
            return Err(Error::new(format!(
                "too many in-flight native jobs ({MAX_LIVE_JOBS}); results are probably never being taken"
            )));
        }

        let cancel = Arc::new(AtomicBool::new(false));
        let token = CancelToken {
            flag: Arc::clone(&cancel),
        };
        let job = Arc::new(Job::new(cancel));
        let id = self.jobs.insert_arc(Arc::clone(&job));

        let work: Work = Box::new(move || {
            // Cancelled while still queued — don't start at all.
            if token.is_cancelled() {
                job.settle(JobState::Cancelled);
                return;
            }
            // Second `catch_unwind`, independent of the one in `ffi`: a panic
            // here unwinds a worker thread, not an FFI call, and an unguarded
            // one would silently kill that worker for the rest of the session
            // while leaving the job `Pending` forever — a poll loop that never
            // terminates.
            let outcome = panic::catch_unwind(AssertUnwindSafe(|| f(&token)));
            let state = match outcome {
                Ok(Ok(bytes)) => {
                    if token.is_cancelled() {
                        JobState::Cancelled
                    } else {
                        JobState::Ready(bytes)
                    }
                }
                Ok(Err(e)) => JobState::Failed(e.message().to_string()),
                Err(payload) => {
                    let payload: Box<dyn Any + Send> = payload;
                    JobState::Failed(format!("job panicked: {}", describe_panic(&*payload)))
                }
            };
            job.settle(state);
        });

        // Send failing means every worker is gone (all spawns failed). Drop the
        // job rather than leave it pending forever.
        if self.ensure_started().send(work).is_err() {
            self.jobs.remove(id);
            return Err(Error::new("native job pool has no worker threads"));
        }
        Ok(id)
    }

    pub fn status(&self, id: i64) -> i32 {
        match self.jobs.get(id) {
            Some(job) => job.status(),
            None => STATUS_UNKNOWN,
        }
    }

    /// `Ok(None)` = still running. `Ok(Some)` = here are the bytes, and the job
    /// is gone. `Err` = the job failed, was cancelled, or never existed — also
    /// gone.
    ///
    /// Consuming on failure as well as on success is what keeps a caller that
    /// only ever calls `jobTake` from leaking: every terminal state is
    /// collected exactly once.
    pub fn take(&self, id: i64) -> Result<Option<Vec<u8>>> {
        let Some(job) = self.jobs.get(id) else {
            return Err(Error::bad_handle(id));
        };

        let mut guard = job.lock();
        match std::mem::replace(&mut guard.state, JobState::Pending) {
            JobState::Pending => Ok(None),
            JobState::Ready(bytes) => {
                drop(guard);
                self.jobs.remove(id);
                Ok(Some(bytes))
            }
            JobState::Failed(msg) => {
                drop(guard);
                self.jobs.remove(id);
                Err(Error::new(msg))
            }
            JobState::Cancelled => {
                drop(guard);
                self.jobs.remove(id);
                Err(Error::new(format!("job {id} was cancelled")))
            }
        }
    }

    /// Flips the token and marks the job cancelled if it hadn't finished.
    ///
    /// The entry stays in the table on purpose: a caller that cancels and then
    /// polls gets `STATUS_CANCELLED` rather than `STATUS_UNKNOWN`, which reads
    /// the same as "you made up this id". It is collected by the next `take` or
    /// by the TTL reaper.
    pub fn cancel(&self, id: i64) {
        if let Some(job) = self.jobs.get(id) {
            job.cancel.store(true, Ordering::Relaxed);
            job.settle(JobState::Cancelled);
        }
    }

    fn reap_stale(&self) {
        let now = Instant::now();
        self.jobs.retain(|_, job| {
            let guard = job.lock();
            match guard.settled_at {
                None => true,
                Some(at) => now.duration_since(at) < RESULT_TTL,
            }
        });
    }

    /// Cancels everything, drops the queue, and lets the workers exit once
    /// they finish what they're on.
    ///
    /// Does **not** join: a job that ignores its cancel token would otherwise
    /// hang JVM shutdown, and holding Minecraft's exit hostage to a chat search
    /// is a worse failure than letting a detached thread finish into a void.
    pub fn shutdown(&self) {
        self.jobs.retain(|_, job| {
            job.cancel.store(true, Ordering::Relaxed);
            false
        });
        let mut guard = self.sender.lock().unwrap_or_else(|e| e.into_inner());
        *guard = None;
    }

    pub fn live_count(&self) -> usize {
        self.jobs.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wait_for(pool: &JobPool, id: i64, want: i32) -> i32 {
        for _ in 0..2000 {
            let s = pool.status(id);
            if s == want {
                return s;
            }
            std::thread::sleep(Duration::from_millis(1));
        }
        pool.status(id)
    }

    #[test]
    fn result_round_trips() {
        let pool = JobPool::new();
        let id = pool.submit(|_| Ok(vec![1, 2, 3])).expect("submit");
        assert_eq!(wait_for(&pool, id, STATUS_READY), STATUS_READY);
        assert_eq!(pool.take(id).unwrap(), Some(vec![1, 2, 3]));
        // Taken once, gone forever — a second take must not resurrect it.
        assert!(pool.take(id).is_err());
        assert_eq!(pool.status(id), STATUS_UNKNOWN);
        pool.shutdown();
    }

    #[test]
    fn failure_reaches_java_as_an_error_not_a_hang() {
        let pool = JobPool::new();
        let id = pool.submit(|_| Err(Error::new("boom"))).expect("submit");
        assert_eq!(wait_for(&pool, id, STATUS_FAILED), STATUS_FAILED);
        let err = pool.take(id).expect_err("failed job must not look pending");
        assert!(err.message().contains("boom"));
        pool.shutdown();
    }

    #[test]
    fn panic_in_a_job_does_not_kill_the_worker() {
        let pool = JobPool::new();
        let bad = pool.submit(|_| panic!("worker exploded")).expect("submit");
        assert_eq!(wait_for(&pool, bad, STATUS_FAILED), STATUS_FAILED);
        assert!(
            pool.take(bad)
                .expect_err("panic must surface as failure")
                .message()
                .contains("worker exploded")
        );

        // The pool must still be usable afterwards; that is the whole point of
        // the inner catch_unwind.
        let good = pool.submit(|_| Ok(vec![9])).expect("submit");
        assert_eq!(wait_for(&pool, good, STATUS_READY), STATUS_READY);
        assert_eq!(pool.take(good).unwrap(), Some(vec![9]));
        pool.shutdown();
    }

    #[test]
    fn cancel_is_observed_by_a_cooperative_job() {
        let pool = JobPool::new();
        let id = pool
            .submit(|token| {
                for _ in 0..10_000 {
                    if token.is_cancelled() {
                        return Ok(Vec::new());
                    }
                    std::thread::sleep(Duration::from_millis(1));
                }
                Ok(vec![0xff])
            })
            .expect("submit");
        pool.cancel(id);
        assert_eq!(pool.status(id), STATUS_CANCELLED);
        assert!(pool.take(id).is_err());
        pool.shutdown();
    }

    #[test]
    fn unknown_ids_are_errors_not_panics() {
        let pool = JobPool::new();
        assert_eq!(pool.status(999_999), STATUS_UNKNOWN);
        assert!(pool.take(999_999).is_err());
        pool.cancel(999_999); // must be a no-op, not a panic
    }
}
