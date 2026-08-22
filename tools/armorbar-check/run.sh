#!/usr/bin/env bash
# Checks the armour bar's point-to-icon arithmetic against hand-worked values.
# See ArmorBarCheck.java for why this is worth a check of its own: every way of
# getting it wrong leaves the bar the right length with one half of one icon in
# the wrong colour, which is invisible in review and in game alike.
#
# NexoArmorBarLayout imports nothing outside java.util, so unlike
# tools/geometry-check this needs no Minecraft on the classpath and no Gradle.
#
# Run from anywhere:  Mod/tools/armorbar-check/run.sh
set -euo pipefail

here=$(cd -- "$(dirname -- "$0")" && pwd)
mod=$(cd -- "$here/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

javac --release 25 -nowarn -d "$work" \
  "$mod/src/main/java/dev/nexoclient/nexomod/hud/NexoArmorBarLayout.java" \
  "$here/ArmorBarCheck.java"

java -cp "$work" dev.nexoclient.nexomod.hud.ArmorBarCheck
