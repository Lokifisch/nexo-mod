#!/usr/bin/env bash
# Every `native` method declared in the mod must have a matching exported
# Java_* symbol in the bundled libnexo_core.so (Tactical has the chunk surface,
# Legit must not export it). Neither compiler checks this; a mismatch is an
# UnsatisfiedLinkError at runtime.
#
# Run after `./gradlew build`:  Mod/tools/jni-symbol-check/run.sh
set -euo pipefail
mod=$(cd -- "$(dirname -- "$0")/../.." && pwd)
fail=0
check() { # <edition> <source dirs...>
  local ed=$1; shift
  local so; so=$(find "$mod/build/generated/natives/$ed" -name 'libnexo_core.so' | head -1)
  [ -n "$so" ] || { echo "no $ed .so (run gradlew build)"; fail=1; return; }
  local syms; syms=$(nm -D --defined-only "$so" | awk '{print $3}' | grep '^Java_' || true)
  while IFS=: read -r file name; do
    local cls pkg
    cls=$(basename "$file" .java)
    pkg=$(grep -m1 '^package ' "$file" | sed 's/package //; s/;//; s/\./_/g')
    grep -qx "Java_${pkg}_${cls}_${name}" <<<"$syms" || { echo "MISSING in $ed: Java_${pkg}_${cls}_${name}"; fail=1; }
  done < <(grep -rEo --include=*.java 'native +[A-Za-z\[\]<>]+ +[A-Za-z0-9]+\(' "$@" | sed -E 's/native +[^ ]+ +([A-Za-z0-9]+)\(/\1/')
}
check legit "$mod/src/main"
check tactical "$mod/src/main" "$mod/src/tactical"
[ "$fail" = 0 ] && echo "jni-symbol-check: OK"
exit "$fail"
