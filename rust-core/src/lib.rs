//! Nexo Mod's native core.
//!
//! This crate is loaded by the JVM (see
//! `dev.nexoclient.nexomod.nativecore.NativeLoader`) and exists for the handful
//! of features where the JVM is the wrong tool: a chat history that has to stay
//! searchable across years of messages without holding it all on the Java heap,
//! per-line log scrubbing on a hot path, chat-filter matching against dozens of
//! user patterns per message, and a chunk-history store that would otherwise be
//! a very large `HashMap` sitting in the same heap the game is trying to render
//! from.
//!
//! Everything crossing the boundary goes through [`ffi`], and the rules that
//! make that boundary survivable are documented there and in `FFI_CONTRACT.md`
//! (the file agents 2–4 read). The short version:
//!
//! * Java holds **handles**, never pointers ([`registry`]).
//! * Long work returns a **job id** and is polled ([`jobs`]).
//! * Every entry point is wrapped in `catch_unwind`, because an unwind across
//!   the FFI boundary is undefined behaviour and takes Minecraft with it.
//! * Failures return a **sentinel** and stash a message for
//!   `nativeLastError()`; nothing throws a Java exception from native code.
//!
//! The mod must run without this library at all — every native feature is
//! optional and disables itself when `NexoNative.isAvailable()` is false. Only
//! Linux x86-64 can be built on the current dev machine, so that path is the
//! normal case for Windows and macOS users until release builds cross-compile.

pub mod chatdb;
/// Only in the full variant — see the `full` feature in `Cargo.toml`.
#[cfg(feature = "full")]
pub mod chunks;
pub mod error;
pub mod ffi;
pub mod filter;
pub mod jobs;
pub mod payload;
pub mod registry;
pub mod scrub;
/// Shared SQLite plumbing for `chatdb` and `chunks` — connection setup, the
/// reader pool, and cancellation. Not part of the FFI surface.
pub mod sqlite;

/// Bumped on any breaking change to the FFI surface — a removed function, a
/// changed signature, a renumbered constant, a payload layout change.
///
/// `NativeLoader` extracts the library to a fresh temp directory each launch,
/// so a stale copy shouldn't normally be loadable at all; this exists for the
/// cases where it is anyway (a developer pointing `nexomod.nativecore.library`
/// at an old `target/release`, a packaging mistake that ships mismatched
/// halves). Java checks it at bootstrap and refuses the library on a mismatch,
/// which degrades to "native features off" instead of to signature-mismatch
/// crashes that look like JVM bugs.
pub const ABI_VERSION: i32 = 2;

/// [`FEATURES`] bit: the chunk-history surface
/// (`dev.nexoclient.nexomod.full.nativecore.NexoNativeChunks`) is exported.
///
/// Mirrored as `NexoNative.FEATURE_CHUNK_HISTORY`.
pub const FEATURE_CHUNK_HISTORY: i32 = 1 << 0;

/// What this build actually exports, beyond the surface every build has.
///
/// The ABI version answers "do the two halves agree on the *shape* of the
/// interface"; this answers "which optional parts are present". They are
/// separate because the light and full libraries share one ABI and differ only
/// in which feature-gated symbols they contain — collapsing that into the ABI
/// version would make the light library look like a stale full one.
///
/// Java reads it once at bootstrap. The reason it exists at all is the runtime
/// override `-Dnexomod.nativecore.library=…`: without it, pointing a full jar
/// at a light build turns the first chunk-history call into an
/// `UnsatisfiedLinkError`, which is an `Error` and slips past
/// `catch (Exception)`.
#[cfg(feature = "full")]
pub const FEATURES: i32 = FEATURE_CHUNK_HISTORY;
#[cfg(not(feature = "full"))]
pub const FEATURES: i32 = 0;

/// Human-readable build identity, surfaced in the log line the mod prints when
/// the library loads and in crash reports.
///
/// The variant is in there deliberately: "chunk history does nothing" and "this
/// is a light build" are the same fact, and the log line is where anyone will
/// look first.
pub fn version() -> String {
    format!(
        "{} (abi {}, {} build, features 0x{:x})",
        env!("CARGO_PKG_VERSION"),
        ABI_VERSION,
        if cfg!(feature = "full") {
            "full"
        } else {
            "light"
        },
        FEATURES
    )
}
