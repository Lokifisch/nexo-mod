# Shared account store

Specification for one **global** account store, written and read by both
Nexo Mod (Java) and Nexo Client (Rust), so signing in anywhere makes the
account available everywhere: the launcher, and every instance.

Nothing here is implemented yet on the Client side, and the Mod still writes
per-instance. This document exists because the format has to be reproduced
byte-for-byte across two languages, and "silently fails to decrypt" is the
failure mode when it isn't.

## Why this is delicate

The key is derived from hardware identifiers, not stored. If the Rust and
Java derivations disagree by so much as a trailing space, neither side
errors usefully — the ciphertext simply fails its GCM tag check and the
accounts look corrupt. **Any change to derivation must bump the version
string and be tested by a real cross-language round trip**, not by reading
both implementations and concluding they match.

## Location (needs changing on both sides)

Today the Mod writes `FabricLoader.getConfigDir()/nexomod-accounts.dat`,
which is **per-instance** — accounts don't even sync between two instances.
The shared store must live at the same OS path the Client already uses
(`nexo-core`'s `Paths::discover`, via the `directories` crate):

| OS | Path |
|---|---|
| Linux | `~/.local/share/nexo/accounts.dat` |
| Windows | `%APPDATA%\Nexo\data\accounts.dat` |
| macOS | `~/Library/Application Support/dev.nexoclient.nexo/accounts.dat` |

The Java side must reproduce that resolution rather than use its config dir.
Note the Linux path honours `$XDG_DATA_HOME` when set — `directories` does,
so Java must too.

## Key derivation (`nexomod-hwkey-v1`)

Collect `label=value` strings. A value is included only if non-null and
non-blank, and is `trim()`ed. Order matters and is fixed per platform.

**Linux**, in this exact order:

1. `cpu.name` — first `model name` or `Model` field in `/proc/cpuinfo`
2. `cpu.serial` — first `Serial` field in `/proc/cpuinfo`
3. `board.name` — `/sys/class/dmi/id/board_vendor` and `board_name`, each
   trimmed, joined with a single space, blanks skipped
4. `board.serial` — `/sys/class/dmi/id/board_serial` (root-only on most
   distros, so normally absent — that's fine, just be consistent)
5. `gpu` — one entry per `/sys/class/drm/card<N>` matching `card\d+`. Value
   is `vendor`, `device`, `subsystem_vendor`, `subsystem_device` from that
   card's `device/` directory, joined with single spaces, blanks skipped.
   **Entries are sorted** before being added, because the OS does not
   guarantee enumeration order and an unsorted list would change the key
   between boots on multi-GPU machines.

Only the first line of each `/sys` file is read.

**Windows** and **macOS** collect the equivalent fields via one PowerShell
CIM query and `sysctl`/`ioreg`/`system_profiler` respectively — see
`HardwareKey.java`. Same labels, same rules, GPUs likewise sorted.

Then:

```
digest = SHA-256()
digest.update("nexomod-hwkey-v1"        as UTF-8)
for part in parts:
    digest.update(0x0A)                  # single newline byte
    digest.update(part                   as UTF-8)
key = digest.digest()                    # 32 bytes, AES-256
```

If **no** identifiers could be collected, derivation yields no key and both
sides must fail safe: do not persist secrets, and do not delete or truncate
a file that couldn't be verified.

## File format

```
offset 0   8 bytes   HEADER = 'N','E','X','O','A','C','C',0x02
offset 8   12 bytes  GCM nonce (random per write)
offset 20  ...       AES-256-GCM ciphertext, 128-bit tag appended
```

`HEADER` is **also passed as GCM additional authenticated data**, so a file
whose header was tampered with fails the tag check rather than decrypting.
The trailing `0x02` is the format version — bump it for any breaking change.

Confirm the on-disk field order against `AccountStore.java` before writing
the Rust side; this table is from reading the constants, and the write path
should be re-read to be certain.

## Plaintext payload

Gson-serialised, so field names are exactly the Java record component names:

```json
{
  "accounts": [
    {
      "name": "Player",
      "uuid": "0123...",
      "minecraftAccessToken": "...",
      "microsoftRefreshToken": "...",
      "expiresAtEpochSecond": 1786200000,
      "offline": false
    }
  ],
  "activeUuid": "0123..."
}
```

Two schema mismatches to resolve before implementing:

- The Client's `Account` carries `skin_url` and `skin_model`, which this
  schema lacks. Add them as optional fields rather than dropping them, or
  the Client loses skins every time the Mod rewrites the file.
- The Client has no `offline` concept. It must round-trip the field
  untouched instead of discarding it, or offline accounts silently lose
  their flag when the launcher writes.
- Field naming differs (`camelCase` here vs the Client's `snake_case`); the
  Rust structs need explicit `#[serde(rename = ...)]`, and both sides must
  ignore unknown fields so a newer writer doesn't break an older reader.

## Concurrency

Both processes can be running at once — the launcher stays open while the
game runs. Writes must be atomic (write to a temp file in the same
directory, then rename) and readers must tolerate the file changing under
them. A last-writer-wins race can drop an account added by the other
process; if that proves to matter, the fix is to re-read, merge by UUID,
then write, rather than to write a wholesale snapshot.

## Verification (do not skip)

Before releasing either side:

1. Have Java write a store, then have Rust read it and assert the accounts
   match.
2. Have Rust write a store, then have Java read it.
3. Assert both derive an identical key on the same machine — easiest by
   logging the SHA-256 of the key (never the key) from each side.

A test that only exercises one language proves nothing about the format.
