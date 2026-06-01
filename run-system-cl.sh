#!/usr/bin/env bash
# True embedded-mode simulation using -Djava.system.class.loader.
# SDK + App are on -cp from the start; AkkaSystemClassLoader picks them up
# via java.class.path before main() even runs.
# Runtime is isolated and registered as a child after boot.
# Exits 0 only if all integration checks pass.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/checks.sh
source "$SCRIPT_DIR/lib/checks.sh"

mvn install -q

SDK_CP=$(cat boot/target/sdk-cp.txt)
APP_JAR=$(cat boot/target/app-cp.txt)
BOOT_CLASSES=$(pwd)/boot/target/classes

TMPFILE=$(mktemp)
trap "rm -f $TMPFILE" EXIT

java \
  -Djava.system.class.loader=com.example.boot.AkkaSystemClassLoader \
  -cp "${SDK_CP}:${APP_JAR}:${BOOT_CLASSES}" \
  com.example.boot.SystemCLBootMain \
  boot/target/sdk-cp.txt \
  boot/target/runtime-cp.txt \
  boot/target/app-cp.txt \
  2>&1 | tee "$TMPFILE" || true

OUTPUT=$(cat "$TMPFILE")
check_output "$OUTPUT"
