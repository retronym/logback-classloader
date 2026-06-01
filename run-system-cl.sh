#!/usr/bin/env bash
# Runs the system-CL solution and verifies all three logback challenges are solved.
# Exits 0 only if all integration checks pass.
#
# Key constraint: only boot/target/classes goes on -cp (AppClassLoader's view).
# SystemCLBootMain calls addJar() to register sdk + logback + app with
# AkkaSystemClassLoader directly.  This ensures those classes are loaded *by*
# AkkaSystemCL, making getClassLoaderOfObject(configurator) return AkkaSystemCL
# (which has the downward child-search).
#
# If sdk+logback were also on -cp, AppClassLoader would load them first via
# parent delegation, bypassing AkkaSystemCL entirely.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/checks.sh
source "$SCRIPT_DIR/lib/checks.sh"

mvn install -q

BOOT_CLASSES=$(pwd)/boot/target/classes

TMPFILE=$(mktemp)
trap "rm -f $TMPFILE" EXIT

java \
  -Djava.system.class.loader=com.example.boot.AkkaSystemClassLoader \
  -cp "${BOOT_CLASSES}" \
  com.example.boot.SystemCLBootMain \
  boot/target/sdk-cp.txt \
  boot/target/runtime-cp.txt \
  boot/target/app-cp.txt \
  2>&1 | tee "$TMPFILE" || true

OUTPUT=$(cat "$TMPFILE")
check_output "$OUTPUT"
