#!/usr/bin/env bash
# Build all modules, run BootMain, then verify logback is fully configured.
# Exits 0 only if all integration checks pass.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/checks.sh
source "$SCRIPT_DIR/lib/checks.sh"

mvn install -q

TMPFILE=$(mktemp)
trap "rm -f $TMPFILE" EXIT

# Run in a forked JVM; stream output live AND capture for post-run checks.
mvn exec:exec -pl boot 2>&1 | tee "$TMPFILE" || true
OUTPUT=$(cat "$TMPFILE")

check_output "$OUTPUT"
