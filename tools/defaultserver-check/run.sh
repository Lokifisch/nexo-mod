#!/usr/bin/env bash
# Checks what counts as the default server already being listed. NexoDefaultServer
# imports nothing from Minecraft, so this needs no Gradle.
# Run from anywhere:  Mod/tools/defaultserver-check/run.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" "$here/../../src/main/java/dev/nexoclient/nexomod/servers/NexoDefaultServer.java" "$here/DefaultServerCheck.java"
java -ea -cp "$out" DefaultServerCheck
