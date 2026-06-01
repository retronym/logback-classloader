#!/usr/bin/env bash
# Self-attach agent solution: loads the agent dynamically at runtime via
# the Instrumentation Attach API, without -javaagent on the command line.
# Exits 0 only if all integration checks pass.
#
# Note: This must run as a true forked process (not via Maven), so that
# the JVM is in a state where attach is allowed. VirtualMachine.attach()
# to self requires a standard JVM startup path.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/checks.sh
source "$SCRIPT_DIR/lib/checks.sh"

mvn install -q

BOOT_CLASSES=$(pwd)/boot/target/classes
SDK_CP=$(cat boot/target/sdk-cp.txt)
APP_CP=$(cat boot/target/app-cp.txt)
AGENT_JAR=$(pwd)/agent/target/agent-1.0-SNAPSHOT.jar

# Shared classpath includes agent so SelfAttachBootMain can access LogbackBridge,
# and can find the agent JAR via findAgentJar().
SHARED_CP="${SDK_CP}:$(cat boot/target/app-cp.txt):${BOOT_CLASSES}:${AGENT_JAR}"

TMPFILE=$(mktemp)
trap "rm -f $TMPFILE" EXIT

# Run as a true forked process (not inside Maven's JVM) so attach-to-self
# is allowed.  Standard Java startup allows VirtualMachine.attach(pid).
java \
  -Djdk.attach.allowAttachSelf \
  -cp "${SHARED_CP}" \
  com.example.boot.SelfAttachBootMain \
  boot/target/sdk-cp.txt \
  boot/target/runtime-cp.txt \
  boot/target/app-cp.txt \
  "${AGENT_JAR}" \
  2>&1 | tee "$TMPFILE" || true

OUTPUT=$(cat "$TMPFILE")
check_output "$OUTPUT"
