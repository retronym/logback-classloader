#!/usr/bin/env bash
# True embedded-mode simulation: SDK + App on the actual system classloader.
# The agent rewrites Loader.getClassLoaderOfObject() to return the bridge CL.
# Exits 0 only if all integration checks pass.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/checks.sh
source "$SCRIPT_DIR/lib/checks.sh"

mvn install -q

SDK_CP=$(cat boot/target/sdk-cp.txt)
APP_JAR=$(cat boot/target/app-cp.txt)
AGENT_JAR=$(pwd)/agent/target/agent-1.0-SNAPSHOT.jar
BOOT_CLASSES=$(pwd)/boot/target/classes

TMPFILE=$(mktemp)
trap "rm -f $TMPFILE" EXIT

# In true embedded mode SDK+App are on the system classpath (via -cp here).
# Only runtime.jar is isolated in a URLClassLoader built by EmbeddedBootMain.
java \
  -javaagent:"${AGENT_JAR}" \
  -cp "${SDK_CP}:${APP_JAR}:${BOOT_CLASSES}:${AGENT_JAR}" \
  com.example.boot.EmbeddedBootMain \
  boot/target/sdk-cp.txt \
  boot/target/runtime-cp.txt \
  boot/target/app-cp.txt \
  2>&1 | tee "$TMPFILE" || true

OUTPUT=$(cat "$TMPFILE")
check_output "$OUTPUT"
