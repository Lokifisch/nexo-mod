#!/usr/bin/env bash
# Checks the cape physics: hangs at rest, lifts when running, flips upside down
# in a fast fall, keeps its rows one row long, and stays out of the body.
# NexoCapePhysics imports nothing from Minecraft, so this is plain javac.
# Run from anywhere:  Mod/tools/cape-physics-check/run.sh
set -euo pipefail
here=$(cd -- "$(dirname -- "$0")" && pwd)
mod=$(cd -- "$here/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac --release 25 -nowarn -d "$work" \
  "$mod/src/main/java/dev/nexoclient/nexomod/capes/NexoCapePhysics.java" \
  "$here/CapePhysicsCheck.java"
java -cp "$work" dev.nexoclient.nexomod.capes.CapePhysicsCheck
