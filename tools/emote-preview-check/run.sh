#!/usr/bin/env bash
# The shop's menu preview against the real starter animations. Run from anywhere,
# with JDK 25 or newer on PATH.
set -euo pipefail
here=$(cd -- "$(dirname -- "$0")" && pwd)
mod=$(cd -- "$here/../.." && pwd)
gson=$(find ~/.gradle ~/.local/share/nexo/libraries -name 'gson-*.jar' ! -name '*sources*' 2>/dev/null | head -1)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac --release 25 -nowarn -cp "$gson" -d "$work" \
  "$mod/src/main/java/dev/nexoclient/nexomod/emotes/NexoEmotePreview.java" "$here/Check.java"
java -cp "$work:$gson" Check "$mod/src/main/resources/assets/nexomod/player_animations/starter.json"
