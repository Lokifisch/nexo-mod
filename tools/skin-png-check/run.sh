#!/bin/sh
# Plain javac check of the wardrobe's PNG test, no Gradle classpath needed.
set -e
here=$(dirname "$0")
out=$(mktemp -d)
javac -d "$out" "$here/../../src/main/java/dev/nexoclient/nexomod/wardrobe/NexoPngCheck.java" "$here/Check.java"
java -cp "$out" Check
rm -rf "$out"
