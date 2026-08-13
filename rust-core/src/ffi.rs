//! The JNI surface. Every symbol here is called from
//! `dev.nexoclient.nexomod.nativecore.NexoNative`; `FFI_CONTRACT.md` is the
//! normative description and this file must agree with it.
//!
//! Four rules hold for every function below, and breaking any of them is a
//! Minecraft crash rather than a compile error, so they are worth stating
//! plainly:
//!
//! 1. **No unwinding past an `extern "system"` frame.** That is undefined
//!    behaviour — in practice a SIGSEGV or an abort with no Java stack trace,
//!    in a process whose crash reports get read by people who will reasonably
//!    blame some other mod. [`EnvUnowned::with_env`] wraps the body in
//!    `catch_unwind` for us, and [`resolve`] turns the caught panic into a
//!    sentinel. Nothing bypasses [`ffi`].
//!
//! 2. **Sentinels, not exceptions.** jni-rs offers `ErrorPolicy` implementations
//!    that throw (`ThrowRuntimeExAndDefault`); this crate deliberately doesn't
//!    use them. Throwing would make every call site need a `try`/`catch` for
//!    conditions that are all "this optional feature isn't available", and the
//!    mod's whole native story is that it degrades quietly. So: `0` for handles
//!    and job ids, `false` for booleans, `null` for objects, `-1` for
//!    `filterTest`, and a message left in the thread-local that
//!    `nativeLastError()` reads back.
//!
//! 3. **The error slot is cleared on entry**, by [`ffi`] itself. So after any
//!    call that returned a sentinel, `nativeLastError()` describes *that* call
//!    and not something from ten minutes ago. It is thread-local, so two Java
//!    threads failing at once don't overwrite each other.
//!
//! 4. **No JVM access off the calling thread.** `Env` is only ever a reference
//!    inside `with_env`'s closure, so it cannot escape into a job closure even
//!    by accident — which is the property `jobs.rs` depends on.

use std::cell::RefCell;
use std::path::PathBuf;
use std::ptr;
use std::sync::{Arc, LazyLock};

use jni::objects::{JByteArray, JClass, JString};
// Note `jboolean` is a Rust `bool` in jni-sys 0.4, not the `u8` older versions
// used — so these functions return `true`/`false`, not `JNI_TRUE`/`JNI_FALSE`.
// That is sound only because Java's `boolean` is a single byte holding 0 or 1;
// it also means a `jboolean` *argument* would be unsound if a caller ever
// passed some other byte, which is one reason nothing here takes one.
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring};
use jni::{Env, EnvUnowned, Outcome};

use crate::chatdb::{ChatDb, ChatRecord};
#[cfg(feature = "full")]
use crate::chunks::{ChunkRecord, ChunkStore};
use crate::error::{Error, Result, describe_panic};
use crate::filter::{ChatFilter, FilterAction};
use crate::jobs;
use crate::payload;
use crate::registry::{INVALID_HANDLE, Registry};
use crate::scrub::Scrubber;

/// Upper bound on `chatDbSearchAsync`'s `limit`. A search screen shows tens of
/// rows; anything past this is a caller passing `Integer.MAX_VALUE` and
/// accidentally asking for the whole history in one `byte[]`.
const MAX_SEARCH_LIMIT: usize = 10_000;

// One registry per object type. They share a global handle sequence (see
// `registry.rs`), so a handle from one is never a valid handle in another.
// `LazyLock` because `Registry::new` can't be `const` (see there).
static CHAT_DBS: LazyLock<Registry<ChatDb>> = LazyLock::new(Registry::new);
static SCRUBBERS: LazyLock<Registry<Scrubber>> = LazyLock::new(Registry::new);
static FILTERS: LazyLock<Registry<ChatFilter>> = LazyLock::new(Registry::new);
#[cfg(feature = "full")]
static CHUNK_STORES: LazyLock<Registry<ChunkStore>> = LazyLock::new(Registry::new);

thread_local! {
    static LAST_ERROR: RefCell<Option<String>> = const { RefCell::new(None) };
}

fn set_last_error(message: impl Into<String>) {
    let message = message.into();
    LAST_ERROR.with(|slot| *slot.borrow_mut() = Some(message));
}

fn clear_last_error() {
    LAST_ERROR.with(|slot| *slot.borrow_mut() = None);
}

/// Maps an outcome onto a JNI return value, recording why on the failure paths.
///
/// Split out from [`ffi`] purely so it can be unit-tested: an `EnvUnowned` can
/// only come from the JVM, but this — the part with the actual decisions in it
/// — doesn't need one.
fn resolve<R: Copy>(fallback: R, outcome: Outcome<R, Error>) -> R {
    match outcome {
        Outcome::Ok(value) => value,
        Outcome::Err(e) => {
            set_last_error(e.message());
            fallback
        }
        Outcome::Panic(payload) => {
            set_last_error(format!(
                "panic in native code: {}",
                describe_panic(&*payload)
            ));
            fallback
        }
    }
}

/// The wrapper every entry point goes through.
///
/// `R: Copy` covers every JNI return type (integers, `bool`, raw pointers,
/// `()`), so the fallback can be used on either failure path without cloning.
fn ffi<'local, R: Copy>(
    unowned: &mut EnvUnowned<'local>,
    fallback: R,
    body: impl FnOnce(&mut Env<'local>) -> Result<R>,
) -> R {
    clear_last_error();
    resolve(fallback, unowned.with_env(body).into_outcome())
}

/// Reads a Java string argument, rejecting `null` with a message that names the
/// parameter — "argument `path` must not be null" beats a bare
/// NullPointerException-equivalent when the call came from four layers of Java
/// away.
fn arg_str(env: &Env<'_>, name: &str, value: &JString) -> Result<String> {
    if value.is_null() {
        return Err(Error::new(format!("argument `{name}` must not be null")));
    }
    Ok(value.try_to_string(env)?)
}

fn arg_path(env: &Env<'_>, name: &str, value: &JString) -> Result<PathBuf> {
    Ok(PathBuf::from(arg_str(env, name, value)?))
}

/// The only current caller is `chunkSnapshot`, which is behind the `full`
/// feature — hence the `allow` rather than a `#[cfg]`. It is a general argument
/// helper, not a full-only one, and gating it would make adding a `byte[]`
/// parameter to a non-gated function fail to compile for no reason.
#[cfg_attr(not(feature = "full"), allow(dead_code))]
fn arg_bytes(env: &Env<'_>, name: &str, value: &JByteArray) -> Result<Vec<u8>> {
    if value.is_null() {
        return Err(Error::new(format!("argument `{name}` must not be null")));
    }
    Ok(env.convert_byte_array(value)?)
}

/// Allocates a Java string and hands back the raw reference.
///
/// The reference is a *local* one, and `with_env` deliberately does not push a
/// JNI frame of its own, so it lives in the caller's frame and the JVM frees it
/// when the native method returns — exactly the lifetime a return value needs.
fn ret_str(env: &mut Env<'_>, value: &str) -> Result<jstring> {
    Ok(JString::from_str(env, value)?.into_raw())
}

fn ret_bytes(env: &mut Env<'_>, value: &[u8]) -> Result<jbyteArray> {
    Ok(env.byte_array_from_slice(value)?.into_raw())
}

fn chat_db(handle: jlong) -> Result<Arc<ChatDb>> {
    CHAT_DBS
        .get(handle)
        .ok_or_else(|| Error::bad_handle(handle))
}

fn scrubber(handle: jlong) -> Result<Arc<Scrubber>> {
    SCRUBBERS
        .get(handle)
        .ok_or_else(|| Error::bad_handle(handle))
}

fn chat_filter(handle: jlong) -> Result<Arc<ChatFilter>> {
    FILTERS.get(handle).ok_or_else(|| Error::bad_handle(handle))
}

#[cfg(feature = "full")]
fn chunk_store(handle: jlong) -> Result<Arc<ChunkStore>> {
    CHUNK_STORES
        .get(handle)
        .ok_or_else(|| Error::bad_handle(handle))
}

// ---------------------------------------------------------------------------
// General
// ---------------------------------------------------------------------------

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_nativeVersion<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    ffi(&mut env, ptr::null_mut(), |env| {
        ret_str(env, &crate::version())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_nativeAbiVersion<
    'local,
>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jint {
    // Deliberately not wrapped: returning a constant cannot fail or panic, and
    // Java calls this *before* trusting anything else in the library, so it
    // must not depend on any of the machinery whose compatibility it checks.
    crate::ABI_VERSION
}

/// Which feature-gated parts of the surface this build exports
/// ([`crate::FEATURES`]).
///
/// Same reasoning as `nativeAbiVersion` for not being wrapped: it returns a
/// compile-time constant, and Java calls it during bootstrap to decide what it
/// is allowed to call afterwards, so it must not depend on anything it is being
/// asked about.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_nativeFeatures<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jint {
    crate::FEATURES
}

/// The one function that does **not** clear the error slot — clearing it here
/// would mean reading the error destroys it, and a caller that logs it twice
/// (once at the call site, once in a crash handler) would get `null` the second
/// time.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_nativeLastError<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    let message = LAST_ERROR.with(|slot| slot.borrow().clone());
    let Some(message) = message else {
        return ptr::null_mut();
    };
    // Any failure allocating the string collapses to `null`, which the caller
    // already has to handle (it means "no error recorded"). Reporting a
    // failure-to-report-a-failure has nowhere useful to go.
    match env.with_env(|env| ret_str(env, &message)).into_outcome() {
        Outcome::Ok(value) => value,
        _ => ptr::null_mut(),
    }
}

/// Cancels every job and drops every live handle.
///
/// Called from the mod's shutdown path. The JVM never unloads a library it has
/// loaded, so this is not about freeing the code — it is about not leaving
/// worker threads and open files behind in a JVM that lingers after the game
/// window closes. Safe to call twice; handles obtained before it are simply
/// dead afterwards, which the registry reports as `bad_handle` rather than
/// undefined behaviour.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_nativeShutdown<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) {
    ffi(&mut env, (), |_env| {
        jobs::pool().shutdown();
        CHAT_DBS.clear();
        SCRUBBERS.clear();
        FILTERS.clear();
        #[cfg(feature = "full")]
        CHUNK_STORES.clear();
        Ok(())
    })
}

/// Diagnostics: a job that succeeds after `delayMillis` with an empty payload.
///
/// Exists so the Java side's submit → poll → take → decode loop can be built
/// and tested against a *successful* job before any of the real producers are
/// implemented. Without it the only reachable path today is the failure path,
/// and "polling works" and "polling works when it fails" are different claims.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_nativeSelfTestJob<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    delay_millis: jint,
) -> jlong {
    ffi(&mut env, INVALID_HANDLE, |_env| {
        // Clamped rather than validated: this is a test hook, and a caller
        // passing a negative or absurd delay wants "immediately" or "a bit",
        // not an error dialog.
        let delay = std::time::Duration::from_millis(delay_millis.clamp(0, 10_000) as u64);
        jobs::pool().submit(move |cancel| {
            let step = std::time::Duration::from_millis(10);
            let mut slept = std::time::Duration::ZERO;
            while slept < delay && !cancel.is_cancelled() {
                let chunk = step.min(delay - slept);
                std::thread::sleep(chunk);
                slept += chunk;
            }
            Ok(payload::empty(payload::KIND_EMPTY))
        })
    })
}

// ---------------------------------------------------------------------------
// Chat database
// ---------------------------------------------------------------------------

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_chatDbOpen<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jlong {
    ffi(&mut env, INVALID_HANDLE, |env| {
        let path = arg_path(env, "path", &path)?;
        Ok(CHAT_DBS.insert(ChatDb::open(&path)?))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_chatDbClose<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    ffi(&mut env, (), |_env| {
        if !CHAT_DBS.remove(handle) {
            // Reported rather than ignored: a double close is a Java-side
            // lifecycle bug, and this is the only place it can be noticed.
            return Err(Error::bad_handle(handle));
        }
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_chatDbInsert<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    ts_millis: jlong,
    server: JString<'local>,
    sender: JString<'local>,
    message: JString<'local>,
) -> jboolean {
    ffi(&mut env, false, |env| {
        let db = chat_db(handle)?;
        let record = ChatRecord {
            ts_millis,
            server: arg_str(env, "server", &server)?,
            sender: arg_str(env, "sender", &sender)?,
            message: arg_str(env, "message", &message)?,
        };
        db.insert(record)?;
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_chatDbSearchAsync<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    query: JString<'local>,
    limit: jint,
) -> jlong {
    ffi(&mut env, INVALID_HANDLE, |env| {
        let db = chat_db(handle)?;
        let query = arg_str(env, "query", &query)?;
        if limit <= 0 {
            return Err(Error::new(format!("limit must be positive, got {limit}")));
        }
        let limit = (limit as usize).min(MAX_SEARCH_LIMIT);
        // The `Arc<ChatDb>` moves into the worker, so a `chatDbClose` racing
        // this search cannot free the database out from under it — the close
        // only drops the registry's reference.
        jobs::pool().submit(move |cancel| {
            let rows = db.search(&query, limit, cancel)?;
            Ok(crate::chatdb::encode_search_results(&rows))
        })
    })
}

// ---------------------------------------------------------------------------
// Job pool
// ---------------------------------------------------------------------------

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_jobStatus<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    job_id: jlong,
) -> jint {
    ffi(&mut env, jobs::STATUS_UNKNOWN, |_env| {
        Ok(jobs::pool().status(job_id))
    })
}

/// Convenience over `jobStatus`, kept because it is what a poll loop actually
/// wants to write. Note it is `false` for a *failed* job too — a caller that
/// only ever asks this question will spin forever on a failure, which is why
/// `jobStatus` exists alongside it and why `jobTake` reports the failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_jobIsReady<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    job_id: jlong,
) -> jboolean {
    ffi(&mut env, false, |_env| {
        Ok(jobs::pool().status(job_id) == jobs::STATUS_READY)
    })
}

/// `null` means either "not finished yet" or "failed" — distinguish with
/// `jobStatus`, or just read `nativeLastError()`, which is non-null only in the
/// failure case because this function clears it on entry.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_jobTake<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    job_id: jlong,
) -> jbyteArray {
    ffi(&mut env, ptr::null_mut(), |env| {
        match jobs::pool().take(job_id)? {
            Some(bytes) => ret_bytes(env, &bytes),
            None => Ok(ptr::null_mut()),
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_jobCancel<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    job_id: jlong,
) {
    ffi(&mut env, (), |_env| {
        // Intentionally silent for an unknown id: cancelling something that
        // already finished and was collected is the normal shape of a screen's
        // close handler, not an error.
        jobs::pool().cancel(job_id);
        Ok(())
    })
}

// ---------------------------------------------------------------------------
// Log scrubber
// ---------------------------------------------------------------------------

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_scrubberCreate<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jlong {
    ffi(&mut env, INVALID_HANDLE, |_env| {
        Ok(SCRUBBERS.insert(Scrubber::new()))
    })
}

/// Returns `null` on failure, and `null` must be treated as **"do not publish
/// this line"** — never as "publish it unchanged". See `scrub.rs`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_scrub<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    line: JString<'local>,
) -> jstring {
    ffi(&mut env, ptr::null_mut(), |env| {
        let scrubber = scrubber(handle)?;
        let line = arg_str(env, "line", &line)?;
        let cleaned = scrubber.scrub(&line)?;
        ret_str(env, &cleaned)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_scrubberDestroy<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    ffi(&mut env, (), |_env| {
        if !SCRUBBERS.remove(handle) {
            return Err(Error::bad_handle(handle));
        }
        Ok(())
    })
}

// ---------------------------------------------------------------------------
// Chat filter
// ---------------------------------------------------------------------------

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_filterCreate<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jlong {
    ffi(&mut env, INVALID_HANDLE, |_env| {
        Ok(FILTERS.insert(ChatFilter::new()))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_filterAddPattern<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    regex: JString<'local>,
    action: jint,
) -> jboolean {
    ffi(&mut env, false, |env| {
        let filter = chat_filter(handle)?;
        let pattern = arg_str(env, "regex", &regex)?;
        let action = FilterAction::from_i32(action)?;
        filter.add_pattern(&pattern, action)?;
        Ok(true)
    })
}

/// `-1` on failure. Callers must treat anything negative as `ALLOW`: this
/// filter is fail-open by design (see `filter.rs`), because a broken filter
/// that hides chat is invisible to the player and a broken filter that shows it
/// is merely annoying.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_filterTest<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    message: JString<'local>,
) -> jint {
    ffi(&mut env, -1, |env| {
        let filter = chat_filter(handle)?;
        let message = arg_str(env, "message", &message)?;
        Ok(filter.test(&message)?.as_i32())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_nativecore_NexoNative_filterDestroy<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    ffi(&mut env, (), |_env| {
        if !FILTERS.remove(handle) {
            return Err(Error::bad_handle(handle));
        }
        Ok(())
    })
}

// ---------------------------------------------------------------------------
// Chunk history — full builds only (Cargo feature `full`)
// ---------------------------------------------------------------------------
//
// These four symbols do not exist in a light build, and neither do their Java
// declarations: they live in
// `src/full/java/dev/nexoclient/nexomod/full/nativecore/NexoNativeChunks.java`,
// which is only compiled into the full jar. That is why the symbol names below
// say `full_nativecore_NexoNativeChunks` rather than `nativecore_NexoNative` —
// JNI derives the symbol from the declaring class's package, so moving the
// declarations moved the symbols.
//
// The point of moving them rather than leaving the declarations in `NexoNative`
// and gating only the Rust side: with the declarations in `src/main`, "light
// code must never call these" is a rule someone has to remember, and forgetting
// it costs an `UnsatisfiedLinkError` at runtime. With them in `src/full` it is a
// compile error in the light build, which is the only kind of guarantee worth
// having across an FFI boundary.

#[cfg(feature = "full")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_full_nativecore_NexoNativeChunks_chunkStoreOpen<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jlong {
    ffi(&mut env, INVALID_HANDLE, |env| {
        let path = arg_path(env, "path", &path)?;
        Ok(CHUNK_STORES.insert(ChunkStore::open(&path)?))
    })
}

#[cfg(feature = "full")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_full_nativecore_NexoNativeChunks_chunkStoreClose<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    ffi(&mut env, (), |_env| {
        if !CHUNK_STORES.remove(handle) {
            return Err(Error::bad_handle(handle));
        }
        Ok(())
    })
}

#[cfg(feature = "full")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_full_nativecore_NexoNativeChunks_chunkSnapshot<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    dimension: JString<'local>,
    chunk_x: jint,
    chunk_z: jint,
    ts_millis: jlong,
    payload: JByteArray<'local>,
) -> jboolean {
    ffi(&mut env, false, |env| {
        let store = chunk_store(handle)?;
        let record = ChunkRecord {
            dimension: arg_str(env, "dimension", &dimension)?,
            chunk_x,
            chunk_z,
            ts_millis,
            // Copied out of the JVM heap immediately. Holding a pinned
            // primitive array across a store call would block GC for as long as
            // the write takes.
            payload: arg_bytes(env, "payload", &payload)?,
        };
        store.snapshot(record)?;
        Ok(true)
    })
}

#[cfg(feature = "full")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_nexoclient_nexomod_full_nativecore_NexoNativeChunks_chunkQueryAsync<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    dimension: JString<'local>,
    min_x: jint,
    min_z: jint,
    max_x: jint,
    max_z: jint,
) -> jlong {
    ffi(&mut env, INVALID_HANDLE, |env| {
        let store = chunk_store(handle)?;
        let dimension = arg_str(env, "dimension", &dimension)?;
        // Normalised here rather than rejected: callers build these bounds from
        // two corners (a selection drag, a player position ± radius), and an
        // inverted rectangle silently returning nothing is a bug that costs an
        // afternoon to find.
        let (min_x, max_x) = (min_x.min(max_x), min_x.max(max_x));
        let (min_z, max_z) = (min_z.min(max_z), min_z.max(max_z));
        jobs::pool().submit(move |cancel| {
            let rows = store.query(&dimension, min_x, min_z, max_x, max_z, cancel)?;
            Ok(crate::chunks::encode_query_results(&rows))
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    // These exercise `resolve`, not `ffi`: an `EnvUnowned` can only be produced
    // by the JVM, but everything that decides what Java sees lives in `resolve`.

    #[test]
    fn errors_become_the_fallback_plus_a_message() {
        let out = resolve(-1i32, Outcome::Err(Error::new("nope")));
        assert_eq!(out, -1);
        assert_eq!(
            LAST_ERROR.with(|s| s.borrow().clone()),
            Some("nope".to_string())
        );
    }

    #[test]
    fn panics_become_the_fallback_instead_of_unwinding_into_the_jvm() {
        let payload: Box<dyn std::any::Any + Send> = Box::new("this would kill Minecraft");
        let out = resolve(0i64, Outcome::<i64, Error>::Panic(payload));
        assert_eq!(out, 0);
        let msg = LAST_ERROR.with(|s| s.borrow().clone()).expect("message");
        assert!(msg.contains("this would kill Minecraft"), "got {msg}");
    }

    #[test]
    fn a_successful_call_clears_a_stale_error() {
        set_last_error("old");
        // `ffi` clears on entry; simulate that half here since we can't build an
        // `EnvUnowned`.
        clear_last_error();
        let out = resolve(0i32, Outcome::<i32, Error>::Ok(7));
        assert_eq!(out, 7);
        assert_eq!(
            LAST_ERROR.with(|s| s.borrow().clone()),
            None,
            "a successful call must not leave the previous failure readable"
        );
    }

    #[test]
    fn handles_from_one_registry_are_rejected_by_another() {
        let filter = FILTERS.insert(ChatFilter::new());
        assert!(
            scrubber(filter).is_err(),
            "cross-type handle must not resolve"
        );
        assert!(chat_db(filter).is_err());
        assert!(chat_filter(filter).is_ok());
        FILTERS.remove(filter);
        assert!(chat_filter(filter).is_err(), "closed handle must be dead");
    }
}
