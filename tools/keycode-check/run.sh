#!/usr/bin/env bash
# Checks the pre-26.3 GLFW key code translation (see LegacyGlfwKeys) with
# plain javac — it imports nothing from Minecraft.
#
# Run from anywhere:  Mod/tools/keycode-check/run.sh
set -euo pipefail

here=$(cd -- "$(dirname -- "$0")" && pwd)
mod=$(cd -- "$here/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

"${JAVA_HOME:-/usr/lib/jvm/java-26-openjdk}/bin/javac" --release 25 -nowarn -d "$work" \
  "$mod/src/main/java/dev/nexoclient/nexomod/util/LegacyGlfwKeys.java" \
  "$here/KeycodeCheck.java"

"${JAVA_HOME:-/usr/lib/jvm/java-26-openjdk}/bin/java" -cp "$work" dev.nexoclient.nexomod.util.KeycodeCheck
