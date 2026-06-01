#!/usr/bin/env bash
# Build all modules, generate classpath files, then run BootMain.
set -euo pipefail

mvn install -q
# Forked JVM — clean TCCL, full logback status output
mvn exec:exec -pl boot
