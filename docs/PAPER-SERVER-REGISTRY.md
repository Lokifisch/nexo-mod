# Paper server registry

Specification for `nexo-paper-server.json`, one per hosted Paper server,
written and read by both Nexo Mod (Java) and Nexo Client (Rust) so either
side can start, observe, or stop a server the other side created. Part of
Phase 7 — see `Mod/ROADMAP.md`.

Implemented today: `dev.nexoclient.nexomod.paperserver.PaperServerRecord`
and `PaperServerRegistry` (Java). **Nothing here exists yet on the Client
side** — same situation `SHARED-ACCOUNT-STORE.md` was written in before the
Rust side caught up, and the same reason this doc exists at all: the format
has to be reproduced correctly in a second language, and "the two sides
silently disagree about a field name" is a worse failure mode than a
missing feature.

## Why this is lower-stakes than the account store, but not zero-stakes

Unlike `accounts.dat`, this is plain JSON with no encryption or
machine-bound key derivation — there's no "silently fails to decrypt"
failure mode. The real risk is smaller but still real: field-naming or
`status`/owner-shape drift between two independently-written
implementations, which shows up as a server the launcher can't see, or a
start/stop button that silently no-ops. **Any breaking field change must
bump `schemaVersion`.**

## Location

`<sharedDataDir>/paper-servers/<id>/nexo-paper-server.json`, where
`sharedDataDir` is the exact same OS path `SHARED-ACCOUNT-STORE.md`
documents for `accounts.dat` (`~/.local/share/nexo` on Linux honouring
`XDG_DATA_HOME`, `%APPDATA%\nexoclient\nexo\data` on Windows,
`~/Library/Application Support/dev.nexoclient.nexo` on macOS). The Java
side resolves this via `dev.nexoclient.nexomod.util.NexoPaths.sharedDataDir()`
— extracted out of `auth.AccountStore` when this registry was added, so
both consumers stay in agreement by construction rather than by two copies
of the same platform-detection code. The Rust side should use
`nexo_core::paths::Paths::discover()`, already the source of truth
`NexoPaths` was written to match.

The rest of that same server directory (not part of the JSON, but part of
the layout this file describes):

```
<sharedDataDir>/paper-servers/<id>/
  paper.jar
  eula.txt
  server.properties
  world/                  # or whatever levelName says — see below
  plugins/
  logs/
  nexo-paper-server.json
```

## File format

Plain UTF-8 JSON, Gson-pretty-printed on the Java side (pretty-printing is
a write-time nicety only — readers must not depend on whitespace). Field
names are exactly the Java record component names, camelCase; the Rust
struct needs `#[serde(rename_all = "camelCase")]` or per-field renames to
match, same convention `SHARED-ACCOUNT-STORE.md` already established for
`accounts.dat`.

```json
{
  "schemaVersion": 1,
  "id": "my-world-server",
  "name": "My World (Paper)",
  "minecraftVersion": "26.1.2",
  "paperBuild": 74,
  "levelName": "world",
  "port": 25566,
  "rcon": { "port": 25576, "password": "..." },
  "eula": { "accepted": true, "acceptedAtEpochSecond": 1786300000, "acceptedBy": "mod" },
  "status": "running",
  "owner": { "startedBy": "mod", "pid": 12345, "hostname": "desktop", "startedAtEpochSecond": 1786300000 },
  "sourceWorld": { "originalSaveId": "my-world", "originalSavePath": "/home/user/.../saves/my-world", "convertedAtEpochSecond": 1786300000 },
  "friendHosting": { "tunnelActive": false, "domain": null },
  "createdAtEpochSecond": 1786299000,
  "updatedAtEpochSecond": 1786300000
}
```

Notes that matter more than they look:

- `status` is a plain string, not a Gson/serde enum — one of `converting`,
  `stopped`, `starting`, `running`, `stopping`, `error`
  (`PaperServerRecord.Status`). Keep the Rust side's allowed values in sync
  by hand; there's no shared source of truth for the set beyond this file.
- `paperBuild`, `owner.pid`, `owner.startedAtEpochSecond`,
  `eula.acceptedAtEpochSecond`, `friendHosting.domain` are all nullable —
  absent or unstarted state, not zero. Rust's equivalents should be
  `Option<T>`, not defaulted numbers.
- `sourceWorld.originalSavePath` is an **absolute, platform-native path**,
  not just a name — `originalSaveId` alone isn't enough to find the world
  back, since the Rust side manages multiple instances, each with its own
  `saves/`, while the Mod only ever has one implicit save location. Whoever
  converts a world writes the absolute path it actually copied from; whoever
  reverts it reads that path back rather than reconstructing it from
  `originalSaveId` and a guessed base directory. This was a real gap caught
  before the Rust side existed — the Mod's very first implementation derived
  the revert path by guessing `<gameDir>/saves/<name>`, which cannot work
  once a second product with its own instance layout needs to revert a
  server it didn't create.
- `owner.startedBy` is `"mod"` or `"launcher"` (or `null` when stopped).
  `owner.pid` is a same-machine diagnostic/fallback only — the real control
  path is RCON (see below), never a `kill` by pid as the first resort.
- `rcon.password` is per-server, freshly random
  (`PaperServerFiles.randomRconPassword()`, 18 random bytes,
  base64url-encoded), never reused, never logged, never sent through the
  friend-hosting tunnel — only the game port is tunneled.
- Unknown fields must round-trip untouched on both sides — same rule as the
  account store, for the same reason: a newer writer's fields shouldn't
  vanish when an older reader resaves the file.

## Concurrency

Both products can be running at once, and a server can be started by one
and later stopped by the other. Writes must be atomic (temp file in the
same directory, then rename) — see `PaperServerRegistry.write`. Readers
must tolerate the file changing under them.

**ponytail: no cross-process lock exists yet** (see the comment on
`PaperServerRegistry.write`) — read-modify-write is not atomic across two
processes. This is deliberately not built yet because start/stop are
user-triggered actions, not a high-frequency race in practice; if real
testing shows the mod and launcher actually stepping on each other's writes
(most likely: both updating `status` within the same second during a
handoff), the fix is a lock file in the server directory, not a rewrite of
the JSON format.

## Control channel: RCON, not this file

This registry is state, not a command channel — writing `"status":
"stopping"` here doesn't stop anything. The actual stop/console-command
path is Source RCON, using `rcon.port`/`rcon.password` from this same
record (`dev.nexoclient.nexomod.paperserver.rcon.RconClient` on the Java
side). Either product can open an RCON connection to a server it didn't
start; that's what makes cross-product control possible without a second
IPC mechanism. Update the record's `status` and `owner` only as an
after-the-fact reflection of what RCON (or process exit) already did.

## Verification (do not skip)

Before either side ships against this format:

1. Have Java write a record (`PaperServerDebug`'s `/nexopaperdebug convert`
   exercises this), then have Rust read it and assert every field matches,
   including the nested objects and the null cases.
2. Have Rust write a record, then have Java read it.
3. Round-trip a record with an extra, unrecognised top-level field through
   both languages and confirm it survives.

A test that only exercises one language proves nothing about the format —
same warning `SHARED-ACCOUNT-STORE.md` ends on, and it's just as true here.
