# Nexo Mod native core — FFI contract

**This file is normative.** `dev.nexoclient.nexomod.nativecore.NexoNative`
(Java) and `rust-core/src/ffi.rs` (Rust) are two halves of the same interface
and neither compiler can check the other. If they disagree, the failure is an
`UnsatisfiedLinkError` at runtime, or worse, a signature mismatch that reads
arguments off the stack wrong and crashes the JVM. So: change this file when you
change the surface, and change both sides together.

ABI version: **2** (`ABI_VERSION` in `src/lib.rs`, mirrored in `NexoNative`).
Bump it for any removed function, changed signature, renumbered constant, or
payload layout change. Java refuses to enable the library on a mismatch.
(1 → 2 was the variant split: `nativeFeatures()` was added and the chunk-history
symbols were renamed, so an ABI-1 library is genuinely incompatible.)

### Two builds of this library

The mod ships as two jars from one tree — `nexomod` (full) and `nexomod-light`,
which contains nothing that supplies information or automation vanilla doesn't —
and **each jar bundles its own build of this library**. Light is the default
Cargo feature set; full is `--features full`. Shipping the full `.so` inside the
light jar would put the capability on the player's disk however careful the Java
side is, which is the whole reason the split reaches down here.

So this document describes a **superset**. Everything in §2 is in both builds
except *Chunk history*, which is full-only and is marked as such. Ask
`NexoNative.hasFeature(…)` before using a gated part; see below.

---

## 1. Ground rules

### Symbol naming

Java `dev.nexoclient.nexomod.nativecore.NexoNative.foo` maps to the exported
Rust symbol `Java_dev_nexoclient_nexomod_nativecore_NexoNative_foo`. No method
name in this interface contains an underscore, so no JNI escaping applies, and
none is overloaded, so no signature suffix applies. Keep it that way — both
rules exist to make the mapping readable rather than derived.

The symbol is derived from the **declaring class**, which the full-only surface
uses deliberately: `NexoNativeChunks` lives in
`dev.nexoclient.nexomod.full.nativecore` (i.e. in `src/full`, absent from the
light jar) and therefore binds to
`Java_dev_nexoclient_nexomod_full_nativecore_NexoNativeChunks_…`. A gated
function must never be declared on `NexoNative` — that class is in both jars,
and a light jar declaring a symbol its light library does not export is an
`UnsatisfiedLinkError` waiting for the first caller.

### Availability

`NexoNative.bootstrap()` is called once from `NexoMod.onInitializeClient()`. It
never throws. Afterwards:

* `NexoNative.isAvailable()` — **check this before every use of the API.**
  Calling a native method with no library loaded throws `UnsatisfiedLinkError`,
  which is an `Error` and slips through `catch (Exception)`.
* A missing library produces exactly one WARN line and nothing else. Only
  Linux x86-64 is built on the current dev machine, so *every Windows and macOS
  player is on this path today*. A feature that only works with the library must
  be invisible — not broken, not erroring — when it isn't there.
* `NexoNative.hasFeature(NexoNative.FEATURE_…)` — **additionally** check this
  before any feature-gated call, i.e. anything on `NexoNativeChunks`. It is
  `isAvailable() && (bitmask & …) != 0`, read once at bootstrap from
  `nativeFeatures()`.

`isAvailable()` and `hasFeature()` come apart in exactly one situation, and it
is one developers create routinely: `-Dnexomod.nativecore.library=…` pointing a
full jar at a light build of the library. The library then loads, reports a
matching ABI, and is healthy — and every chunk-history call still dies with an
`UnsatisfiedLinkError`. That is what the feature bitmask exists to catch; it is
not the same question as the ABI version, which asks whether the two halves
agree on the *shape* of the interface rather than which optional parts are
present.

### Errors

Nothing throws. Every function returns a **sentinel** on failure and leaves a
message for `nativeLastError()`:

| Return type | Sentinel |
|---|---|
| `long` (handle or job id) | `0` (`NexoNative.INVALID_HANDLE`) |
| `boolean` | `false` |
| `String`, `byte[]` | `null` |
| `int` (`filterTest` only) | `-1` |

`nativeLastError()` is **thread-local** and is cleared at the start of every
other native call, so immediately after a call that returned a sentinel it
describes *that* call. Reading it does not clear it. `null` means the last call
on this thread succeeded.

### Threading

Every function is safe to call from any Java thread at any time; the handle
tables are `RwLock`-protected and the per-object state is behind its own lock.
There is no thread affinity requirement anywhere in this interface.

What is *not* allowed is the reverse direction: **native code never calls into
Java**, never attaches a thread to the JVM, and never retains a `JNIEnv`. Job
results are therefore polled, not delivered by callback. That is deliberate — a
callback from a Rust worker would run mod code on a thread Minecraft has never
heard of, and an attached-and-not-detached native thread stops the JVM from
shutting down.

Practical consequence for concurrent handle use: two threads may hold the same
handle. Closing it while another thread is mid-operation is safe (the object is
reference-counted and outlives the close); the *next* call on that handle then
fails cleanly with "handle is not live".

### Handles

A handle is an opaque `long`, never a pointer. Handles are unique across the
whole process — a chat-DB handle is not a valid filter handle — and are never
reused after being closed. Passing a stale, wrong-type, or invented handle is a
clean error, never memory corruption.

Handles do not close themselves. Every `…Open`/`…Create` needs a matching
`…Close`/`…Destroy`, or `NexoNative.shutdown()`, which drops everything.

### Ownership of return values

`String` and `byte[]` returns are freshly allocated JVM objects owned by Java
from the moment they are returned — normal garbage-collected objects, nothing to
free. `byte[]` arguments are copied out of the JVM heap during the call and not
retained.

---

## 2. Functions

### General

```java
static native String nativeVersion();
```
Crate version, ABI, variant and feature bitmask, e.g.
`0.4.0 (abi 2, light build, features 0x0)`. For logs and crash reports. Never
fails in practice; `null` on allocation failure. The variant is in the string
deliberately: "chunk history does nothing" and "this is a light build" are the
same fact, and this line is where anyone looks first.

```java
static native int nativeAbiVersion();
```
`ABI_VERSION`. The only function that does no error handling at all — it must
work before anything else is trusted, so it cannot depend on the machinery whose
compatibility it is checking. Cannot fail.

```java
static native int nativeFeatures();
```
Bitmask of the feature-gated parts this build exports; `0` for a light build.

| Bit | `NexoNative` constant | `lib.rs` constant | Gated surface |
|---|---|---|---|
| `1 << 0` | `FEATURE_CHUNK_HISTORY` | `FEATURE_CHUNK_HISTORY` | `NexoNativeChunks` |

Read once at bootstrap; use `NexoNative.hasFeature(…)` afterwards rather than
calling this again. Cannot fail. **Adding a gated surface means adding a bit
here, in `lib.rs`, and in `NexoNative` — three places that have to agree**, plus
the `#[cfg]` on the module and on its entry points.

```java
static native String nativeLastError();
```
The most recent failure on the calling thread, or `null`. The one function that
does **not** clear the error slot, so it can be logged more than once.

```java
static native void nativeShutdown();
```
Cancels all jobs, drops all handles, stops the worker pool. Idempotent. Does not
block on running jobs. Prefer `NexoNative.shutdown()`, which guards it with the
availability check. The library is not unloaded — the JVM never unloads native
libraries — so a later call re-creates the pool as needed.

```java
static native long nativeSelfTestJob(int delayMillis);
```
**Diagnostics only.** A job that succeeds after roughly `delayMillis` (clamped
to 0–10000) with an empty `KIND_EMPTY` payload, and honours cancellation. Exists
so the Java poll/collect loop can be exercised against a job that *succeeds*,
which no real producer does until the storage layer lands. Do not ship a UI
path that depends on it.

### Chat database

```java
static native long    chatDbOpen(String path);
static native void    chatDbClose(long h);
static native boolean chatDbInsert(long h, long tsMillis, String server, String sender, String message);
static native long    chatDbSearchAsync(long h, String query, int limit);
```

* `chatDbOpen` — creates missing parent directories. Returns a handle or `0`.
* `chatDbClose` — returns nothing; a double close is recorded as an error in
  `nativeLastError()` (it is a Java-side lifecycle bug and this is the only
  place it can be noticed).
* `chatDbInsert` — `tsMillis` is `System.currentTimeMillis()`. No argument may
  be `null`. Called per chat packet, so it must stay cheap; do not fsync per
  message.
* `chatDbSearchAsync` — `limit` must be positive and is clamped to 10000.
  Returns a **job id**, not results. Payload kind: `KIND_CHAT_SEARCH`. The
  database stays alive for the duration of the search even if the handle is
  closed underneath it.

### Job pool

```java
static native int     jobStatus(long jobId);
static native boolean jobIsReady(long jobId);
static native byte[]  jobTake(long jobId);
static native void    jobCancel(long jobId);
```

`jobStatus` returns one of:

| Value | `NexoNative` constant | Meaning |
|---|---|---|
| 0 | `JOB_UNKNOWN` | never existed, or already collected |
| 1 | `JOB_PENDING`  | queued or running |
| 2 | `JOB_READY`    | finished; `jobTake` returns the payload |
| 3 | `JOB_FAILED`   | finished badly; `jobTake` returns `null` + last error |
| 4 | `JOB_CANCELLED`| cancelled |

* `jobIsReady` is `status == JOB_READY`. **It is `false` for a failed job too**,
  so a loop written only against it spins forever on failure. Anything with a
  spinner in front of it should poll `jobStatus`.
* `jobTake` consumes the job in every terminal state (ready, failed, cancelled),
  so a caller that only ever calls `jobTake` still cannot leak. `null` means
  "not finished **or** failed" — `nativeLastError()` is non-null only in the
  failure case.
* `jobCancel` is advisory: the job stops at its next cancellation check. Silent
  for an unknown id, since cancelling something that already finished is the
  normal shape of a screen's close handler. The id stays pollable (reporting
  `JOB_CANCELLED`) until taken.
* A settled result nobody collects is dropped after **5 minutes**. At most
  **256** jobs may be live at once; past that `…Async` fails, which means
  something is submitting without collecting.

Poll from the client tick, not from a spin loop:

```java
if (jobId != NexoNative.INVALID_HANDLE) {
    int status = NexoNative.jobStatus(jobId);
    if (status == NexoNative.JOB_READY) {
        byte[] raw = NexoNative.jobTake(jobId);
        jobId = NexoNative.INVALID_HANDLE;
        // ... decode with JobPayloadReader ...
    } else if (status != NexoNative.JOB_PENDING) {
        LOGGER.debug("search failed: {}", NexoNative.lastErrorOrUnknown());
        jobId = NexoNative.INVALID_HANDLE;
    }
}
```

### Log scrubber

```java
static native long   scrubberCreate();
static native String scrub(long h, String line);
static native void   scrubberDestroy(long h);
```

`scrub` is **fail-closed**. `null` means **do not publish this line** — never
"publish it unchanged". A scrubber that silently passes text through on error is
worse than no scrubber, because the caller believes the line was cleaned and
pastes a session token into a public bug report.

### Chat filter

```java
static native long    filterCreate();
static native boolean filterAddPattern(long h, String regex, int action);
static native int     filterTest(long h, String message);
static native void    filterDestroy(long h);
```

`action` and the return of `filterTest`:

| Value | `NexoNative` constant | Meaning |
|---|---|---|
| 0 | `FILTER_ALLOW`     | show normally |
| 1 | `FILTER_HIDE`      | suppress |
| 2 | `FILTER_HIGHLIGHT` | show with emphasis |

`filterTest` is **fail-open**: `-1` on failure, and callers must treat anything
negative as `FILTER_ALLOW`. Opposite policy from the scrubber, on purpose — chat
silently vanishing is a bug nobody reports, chat wrongly appearing is one
everybody does.

`filterAddPattern` rejects an empty pattern. It is also where an invalid regex
must be rejected (so the settings screen can show the error next to the field
the user just typed into).

### Chunk history — FULL BUILDS ONLY

Cargo feature `full`; Rust module `chunks.rs`; entry points `#[cfg]`-gated in
`ffi.rs`. **Declared on a different Java class in a different source set:**

```java
// dev.nexoclient.nexomod.full.nativecore.NexoNativeChunks — src/full, so this
// class is not in the nexomod-light jar at all.
static native long    chunkStoreOpen(String path);
static native void    chunkStoreClose(long h);
static native boolean chunkSnapshot(long h, String dimension, int chunkX, int chunkZ, long tsMillis, byte[] payload);
static native long    chunkQueryAsync(long h, String dimension, int minX, int minZ, int maxX, int maxZ);
```

Why a separate class rather than `NexoNative` plus a guard: JNI derives the
symbol from the declaring class, so these bind to
`Java_dev_nexoclient_nexomod_full_nativecore_NexoNativeChunks_…`, which the
light library does not export — and the class that declares them is not in the
light jar either. Both halves of the light build are then physically incapable
of naming this surface, instead of merely being trusted not to. Declaring them
on `NexoNative` would have made "light build" a runtime `UnsatisfiedLinkError`
(an `Error`, so it slips past `catch (Exception)`); this way it is a compile
error in the only source set that could make the mistake.

Call `NexoNativeChunks.isAvailable()` first — that is
`NexoNative.hasFeature(FEATURE_CHUNK_HISTORY)`, and it is the case that a full
jar can still be running a light library (see *Availability*).

`chunkStoreOpen`/`chunkStoreClose` are additions to the originally specified
surface: `chunkSnapshot` and `chunkQueryAsync` both take a handle, and a handle
has to come from somewhere.

`payload` is **opaque to the native side** — stored and returned byte for byte,
never parsed. Its encoding is entirely the Java side's business, which is what
keeps block-state ids, palette layout and every other thing that changes between
Minecraft versions on the side of the boundary that already tracks them.

`chunkQueryAsync` bounds are inclusive chunk coordinates and are normalised
natively, so either corner order works. Returns a job id; payload kind
`KIND_CHUNK_QUERY`.

---

## 3. Job payload format

Produced by `src/payload.rs`, consumed by
`dev.nexoclient.nexomod.nativecore.JobPayloadReader`. Use that class rather than
re-deriving the layout.

```
magic   4 bytes  "NXJ1"
kind    u8
count   u32      number of records
records count × kind-specific fields
```

Everything is **big-endian**, which is why the Java reader is a plain
`ByteBuffer` with no byte-order handling — that is the point of the choice.

Field encodings:

* `i32` / `i64` — fixed width, big-endian, two's complement.
* `str` — `u16` byte length, then that many **UTF-8** bytes. This is *not*
  `DataInput.readUTF`, which is modified UTF-8 and encodes NUL and
  astral-plane characters differently; chat is full of emoji.
* `bytes` — `u32` byte length, then raw bytes.

Kinds:

| Kind | Name | Record fields, in order |
|---|---|---|
| 0 | `KIND_EMPTY` | *(none — a job that finished and found nothing)* |
| 1 | `KIND_CHAT_SEARCH` | `i64 tsMillis`, `str server`, `str sender`, `str message` |
| 2 | `KIND_CHUNK_QUERY` | `str dimension`, `i32 chunkX`, `i32 chunkZ`, `i64 tsMillis`, `bytes payload` |

Records are read positionally: pulling fields in the wrong order yields garbage,
not an exception. The version lives in the magic, so an unrecognised payload is
rejected outright instead of being misread as an older layout.

---

## 4. Storage-layer behaviour

**All four bodies are implemented** — `chatdb.rs`, `scrub.rs`, `filter.rs`, and
`chunks.rs` (full only). No signature in §2 changed; everything below is
behaviour that was previously unspecified because there was nothing behind the
symbol yet. Nothing here throws or changes a sentinel.

### Limits Java can hit

Each of these is a **refusal with a message on `nativeLastError()`**, not a
silent truncation, so a caller that trips one gets a sentinel rather than a
quietly wrong answer:

| Call | Limit | Over it |
|---|---|---|
| `scrub` | line ≤ 256 KiB | `null` — **fail-closed, do not publish the line** |
| `filterAddPattern` | pattern ≤ 512 bytes; ≤ 256 patterns per filter; compiled program ≤ 256 KiB | `false` |
| `filterTest` | message ≤ 16 KiB | **`FILTER_ALLOW`, unscanned** (fail-open — not an error) |
| `chatDbSearchAsync` | `limit` clamped to 10000; past 32 whitespace terms are dropped | clamped, not refused |
| `chunkSnapshot` | payload ≤ 4 MiB | `false` |
| `chunkQueryAsync` | region ≤ **16384 chunks (128×128)** | job fails; ask for a smaller region |

The chunk-query area limit is the one most likely to surprise: bounds are
inclusive, so a 128×128 region is the largest legal one, and
`Integer.MIN_VALUE..MAX_VALUE` — what "give me everything" looks like before
anyone thinks about it — is refused rather than answered. It exists because
every matched row is copied into a single `byte[]`.

### Behaviour worth knowing at the call site

* **`chatDbSearchAsync` with an empty or unsearchable query is not an error and
  does not match nothing** — it returns the most recent `limit` lines. That is
  what a search screen wants on first open. "Unsearchable" means the text
  contains no alphanumeric character, e.g. `""`, `"   "`, `"***"`.
* **The search box is free text, not a query language.** The input is rewritten
  into a conjunction of literal phrases, so `OR`, `NEAR(`, `sender:`, `-`, `^`
  and stray quotes are all matched as ordinary words rather than parsed. The one
  piece of syntax that *is* honoured is a **trailing `*`** on a term, which is a
  prefix search (`enchant*`). Multiple terms are ANDed.
* Search matches on the **message text only**. `server` and `sender` are stored
  and returned but are not full-text indexed.
* **`filterTest` is first match wins in insertion order** — no priority by
  action, no most-specific heuristic. A rule list is read top to bottom exactly
  as the settings screen displays it, which means an `FILTER_ALLOW` rule placed
  above a `FILTER_HIDE` rule works as a whitelist. Patterns are
  **case-insensitive by default**; a pattern can opt out with the inline flag
  `(?-i)`.
* **`chunkSnapshot` overwrites**, it does not append: `(dimension, chunkX,
  chunkZ)` is unique, and re-observing a chunk replaces the earlier row.
* **The chunk store evicts.** It is capped (100000 chunks by default) and drops
  the oldest-observed rows in a batch once over the cap, so a snapshot written
  months ago may be gone. `chunkQueryAsync` returning nothing for a chunk the
  player has certainly visited is expected behaviour, not a bug.
* Snapshot payloads are **deflate-compressed in native code** and returned
  decompressed, byte for byte. Java should not pre-compress; it would pay for a
  second useless pass. A row whose payload will not decompress (a damaged file)
  is skipped, costing that chunk rather than the whole query.

### Constraints that come from this side of the boundary

1. **Every method takes `&self`.** The registry hands out `Arc<T>` and two Java
   threads can hold the same handle. Put state behind a `Mutex`/`RwLock` inside
   your type. Taking `&mut self` would mean serialising at the registry, which
   would block inserts behind a long search.
2. **Job bodies are pure Rust.** No `JNIEnv`, no `JavaVM`, no callbacks. If you
   want Java to do something with the result, put it in the payload.
3. **Poll `CancelToken` in every long loop** — per row scanned, per chunk
   visited. A job that never checks still completes, it just wastes the work.
4. **Do not set `panic = "abort"`** in any profile. `catch_unwind` is what keeps
   a Rust bug from becoming a hard Minecraft crash, and it is a no-op under
   abort.
5. New dependencies are yours to add. Keep an eye on what they pull in: this
   library ships inside the mod jar for every platform.
6. **`chunks.rs` is behind `#[cfg(feature = "full")]`**, and so are its four
   entry points in `ffi.rs` and the `CHUNK_STORES` registry. Work on it with
   `cargo build --features full` / `cargo test --features full` — a plain
   `cargo build` does not compile the file at all, so it will happily stay green
   over a syntax error. Anything you add that only the chunk store uses needs
   the same gate, or the light build fails on an unused import.

## 5. Building

`cargo build --release` builds the **light** library (default features);
`cargo build --release --features full` builds the full one. `cargo test` for
the unit tests (handle lifecycle, job pool, payload encoding — all offline);
add `--features full` to also run the chunk-store tests.

The Gradle side runs this automatically, **once per variant**: the `cargoBuild`
task in `../build.gradle` builds the host target into
`build/generated/natives/<variant>/<os>-<arch>/`, and each jar takes the one it
needs — `processResources` copies `full/` and `processLightResources` copies
`light/`, both to `assets/nexomod/natives/<os>-<arch>/` inside their jar. The
`<os>-<arch>/<library>` tail matches `NativeLoader.platformDirectory()`; a
disagreement there is invisible until runtime, where it looks like a library
that was built but can never be found.

Each variant gets its own `--target-dir` (`target/variant-light`,
`target/variant-full`). Toggling a Cargo feature invalidates the whole
dependency graph, so a shared directory would mean recompiling SQLite from
source on every build rather than only when it changes.

* **A missing cargo is a warning, not a build failure** — the jar is produced
  without natives and the mod runs with those features off. A missing Rust
  toolchain must not stop someone building the Java side.
* A *host* build that fails to compile **does** fail the Gradle build; a *cross*
  target that fails only warns, since a missing target or linker is expected
  anywhere but the release machine.
* Cross targets are opt-in: `-Pnexo_native_extra_targets=x86_64-pc-windows-gnu,…`
  (or the same key in `gradle.properties`). Each needs `rustup target add` plus a
  linker for that platform.
* `-Pnexo_native_skip=true` skips the native build entirely.
* `-Dnexomod.nativecore.library=/path/to/libnexo_core.so` at *runtime* overrides
  the copy in the jar, so iterating on Rust only needs `cargo build` and a game
  restart.
