#!/usr/bin/env bash
# The mod's ticket and wire code against the real service code and relay.
# Needs EmoteService/.venv (see its README). Run from anywhere.
set -euo pipefail
here=$(cd -- "$(dirname -- "$0")" && pwd)
mod=$(cd -- "$here/../.." && pwd)
service=$mod/../EmoteService
py=$service/.venv/bin/python
work=$(mktemp -d)
relay=
trap '[ -n "$relay" ] && kill "$relay" 2>/dev/null; rm -rf "$work"' EXIT

"$py" - "$work/fixture" <<'P'
import sys, time
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from nexo_emotes import tickets
key = Ed25519PrivateKey.generate()
emote = 'ab' * 32
owner = '12345678-1234-1234-1234-123456789abc'
now = int(time.time())
with open(sys.argv[1], 'w') as out:
    print(tickets.public_bytes(key).hex(), file=out)
    print(emote, file=out)
    print(owner, file=out)
    print(tickets.sign(key, 1, emote, owner, now + 3600).hex(), file=out)
    print(tickets.sign(key, 1, emote, owner, now - 10).hex(), file=out)
P
port=$(( 20000 + RANDOM % 20000 ))
NEXO_RELAY_PORT=$port "$py" -m nexo_emotes.relay &
relay=$!
sleep 1
javac --release 25 -nowarn -d "$work" \
  "$mod/src/main/java/dev/nexoclient/nexomod/emotes/NexoEmoteTicket.java" \
  "$mod/src/main/java/dev/nexoclient/nexomod/emotes/NexoEmoteWire.java" \
  "$here/Check.java"
java -cp "$work" Check "$work/fixture" "ws://127.0.0.1:$port"
