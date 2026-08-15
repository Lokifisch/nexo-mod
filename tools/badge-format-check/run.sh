#!/usr/bin/env bash
# Cross-checks the mod's roster format against the service's.
#
# The service builds a roster from a set of UUIDs; the mod's own
# BadgeRosterFormat then searches that blob for each of them, plus for UUIDs
# that were never added. Both halves of the format have to agree — the hash and
# the unsigned sort order — or this reports a MISS on a member.
#
# Run from the repository root:  Mod/tools/badge-format-check/run.sh
set -euo pipefail

here=$(cd -- "$(dirname -- "$0")" && pwd)
mod=$(cd -- "$here/../.." && pwd)
service=$(cd -- "$mod/../BadgeService" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Deliberately includes UUIDs whose hashes start above 0x7f, since a signed
# comparison would sort exactly those to the wrong end.
members=(
  069a79f4-44e9-4726-a5be-fca90e38aaf5
  853c80ef-3c37-49fd-aa49-938b674adae6
  00000000-0000-0000-0000-000000000000
  ffffffff-ffff-ffff-ffff-ffffffffffff
  61699b2e-d327-4a01-9f1e-0ea8c3f06bc6
  7f4c0d3e-1b2a-4c5d-8e9f-0a1b2c3d4e5f
  c0ffee00-dead-4bee-8fff-1234567890ab
)
strangers=(
  11111111-1111-4111-8111-111111111111
  22222222-2222-4222-8222-222222222222
)

"$service/.venv/bin/python" - "$work/roster.bin" "${members[@]}" <<'PY'
import sys, tempfile
from nexo_badge.store import Store
from nexo_badge.hashing import uuid_hash

out, members = sys.argv[1], sys.argv[2:]
store = Store(tempfile.mktemp(suffix='.db'))
for member in members:
    store.upsert_member(uuid_hash(member))
blob = store.roster_bytes()
open(out, 'wb').write(blob)
print(f'service built a {len(blob)}-byte roster of {len(members)} members')
PY

mkdir -p "$work/classes"
javac -d "$work/classes" \
  "$mod/src/main/java/dev/nexoclient/nexomod/badge/BadgeRosterFormat.java" \
  "$here/FormatCheck.java"

result=$(java -cp "$work/classes" dev.nexoclient.nexomod.badge.FormatCheck \
  "$work/roster.bin" "${members[@]}" "${strangers[@]}")
echo "$result"

misses=$(echo "$result" | head -n "${#members[@]}" | grep -c '^MISS' || true)
hits=$(echo "$result" | tail -n "${#strangers[@]}" | grep -c '^HIT' || true)
if [ "$misses" -ne 0 ] || [ "$hits" -ne 0 ]; then
  echo "FAIL: $misses member(s) not found, $hits stranger(s) wrongly matched"
  exit 1
fi
echo "OK: the mod finds every member the service published, and no stranger"
