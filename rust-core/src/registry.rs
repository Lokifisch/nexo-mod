//! Handle table between the JVM and Rust-owned objects.
//!
//! The obvious way to hand a Rust object to Java is `Box::into_raw() as jlong`
//! and `Box::from_raw()` to free it. That is also the way to hand Java a loaded
//! gun: a `long` field survives a `close()`, gets copied, gets read by a second
//! thread, and dereferencing it after the free is undefined behaviour that
//! surfaces as a JVM segfault with no stack trace — in a *Minecraft* process,
//! where the report lands as "the game crashed randomly".
//!
//! So handles are indices, not addresses. A dead handle is a `HashMap` miss,
//! which is a clean error; a double close is a second miss; and because the
//! counter only ever increases, a stale handle can never be recycled onto a
//! different object.

use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, RwLock, RwLockReadGuard, RwLockWriteGuard};

/// Shared by *every* `Registry` in the process, not per-registry.
///
/// Per-registry counters would each hand out 1, 2, 3…, so a chat-DB handle and
/// a filter handle would collide numerically and `filterDestroy(chatDbHandle)`
/// would silently destroy the wrong object. With one global sequence every live
/// handle is unique process-wide, so a mixed-up handle misses its registry and
/// produces `bad_handle` instead of corruption.
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

/// 0 is never issued, so it doubles as the "failed" sentinel every
/// handle-returning FFI function uses.
pub const INVALID_HANDLE: i64 = 0;

fn next_handle() -> i64 {
    NEXT_HANDLE.fetch_add(1, Ordering::Relaxed)
}

pub struct Registry<T> {
    entries: RwLock<HashMap<i64, Arc<T>>>,
}

impl<T> Registry<T> {
    /// Not `const` — `HashMap::new` isn't, because `RandomState` seeds itself at
    /// runtime. The `static`s in `ffi.rs` therefore wrap this in a `LazyLock`
    /// rather than constructing it in place.
    pub fn new() -> Self {
        Self {
            entries: RwLock::new(HashMap::new()),
        }
    }

    /// Lock poisoning is recovered from rather than propagated. A panic while
    /// holding one of these locks can leave the `HashMap` at worst missing an
    /// insert — there is no cross-entry invariant to break — whereas honouring
    /// the poison would permanently disable the feature for the rest of the
    /// session over a bug that already got caught by `catch_unwind`.
    fn read(&self) -> RwLockReadGuard<'_, HashMap<i64, Arc<T>>> {
        self.entries.read().unwrap_or_else(|e| e.into_inner())
    }

    fn write(&self) -> RwLockWriteGuard<'_, HashMap<i64, Arc<T>>> {
        self.entries.write().unwrap_or_else(|e| e.into_inner())
    }

    /// Takes ownership of `value` and returns the handle Java should hold.
    pub fn insert(&self, value: T) -> i64 {
        self.insert_arc(Arc::new(value))
    }

    /// For callers that need to keep working with the object they just
    /// registered (the job pool hands its `Arc` straight to a worker thread).
    /// Inserting and then `get`ting it back would work too, but only because
    /// handles are never reused — this avoids relying on that for correctness.
    pub fn insert_arc(&self, value: Arc<T>) -> i64 {
        let handle = next_handle();
        self.write().insert(handle, value);
        handle
    }

    /// Clones the `Arc` out rather than lending a guard, so the registry lock is
    /// released before the caller does any real work with the object. Otherwise
    /// one slow chat search would block every other thread's `insert`.
    pub fn get(&self, handle: i64) -> Option<Arc<T>> {
        self.read().get(&handle).cloned()
    }

    /// `false` means the handle was already gone — i.e. a double close, which
    /// callers report rather than treat as success.
    ///
    /// Dropping the `Arc` here only drops *this* reference; a worker thread
    /// still holding one keeps the object alive until it finishes. That is the
    /// entire point of `Arc` over `Box`: closing a handle mid-query cannot pull
    /// the object out from under the query.
    pub fn remove(&self, handle: i64) -> bool {
        self.write().remove(&handle).is_some()
    }

    /// Drops every entry, returning how many there were. Used by
    /// `nativeShutdown` so a client quit doesn't leave file handles open in a
    /// JVM that lingers.
    pub fn clear(&self) -> usize {
        let mut guard = self.write();
        let n = guard.len();
        guard.clear();
        n
    }

    /// Drops every entry the predicate rejects. Exists for the job pool's
    /// reaper, which has to evict results nobody collected without holding the
    /// lock across each decision.
    pub fn retain(&self, mut keep: impl FnMut(i64, &Arc<T>) -> bool) {
        self.write().retain(|&handle, value| keep(handle, value));
    }

    pub fn len(&self) -> usize {
        self.read().len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

impl<T> Default for Registry<T> {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handles_are_never_reused_across_registries() {
        let a: Registry<u32> = Registry::new();
        let b: Registry<u32> = Registry::new();

        let h1 = a.insert(1);
        let h2 = b.insert(2);
        assert_ne!(h1, h2, "distinct registries must not hand out the same id");

        // A handle from `a` must not resolve in `b`, or a mixed-up close would
        // silently destroy the wrong object.
        assert!(b.get(h1).is_none());
        assert!(a.get(h2).is_none());
    }

    #[test]
    fn removed_handle_is_dead_forever() {
        let r: Registry<u32> = Registry::new();
        let h = r.insert(7);
        assert!(r.remove(h));
        assert!(
            !r.remove(h),
            "double close must report failure, not succeed"
        );
        assert!(r.get(h).is_none());

        // The next insert must not land on the freed id.
        let h2 = r.insert(8);
        assert_ne!(h, h2);
    }

    #[test]
    fn outstanding_arc_outlives_removal() {
        let r: Registry<u32> = Registry::new();
        let h = r.insert(42);
        let held = r.get(h).expect("just inserted");
        r.remove(h);
        // Simulates a worker mid-query while Java calls close().
        assert_eq!(*held, 42);
    }
}
