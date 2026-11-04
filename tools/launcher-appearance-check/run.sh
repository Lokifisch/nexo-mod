#!/bin/sh
# Checks the mod's reader of the launcher's appearance.txt. Needs `gradle build` first.
set -e
cd "$(dirname "$0")/../.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac -d "$out" -cp build/classes/java/main tools/launcher-appearance-check/Check.java
java -cp "$out:build/classes/java/main" dev.nexoclient.nexomod.screen.Check
