#!/usr/bin/env bash
# Checks how the window title is put together. NexoWindowTitle imports nothing, so
# this needs no Minecraft on the classpath and no Gradle.
# Run from anywhere:  Mod/tools/windowtitle-check/run.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" "$here/../../src/main/java/dev/nexoclient/nexomod/title/NexoWindowTitle.java" "$here/WindowTitleCheck.java"
java -ea -cp "$out" WindowTitleCheck
