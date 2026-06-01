#!/usr/bin/env bash
# True embedded-mode simulation using -Djava.system.class.loader.
# SDK + App are on -cp from the start; AkkaSystemClassLoader picks them up
# via java.class.path before main() even runs.
# Runtime is isolated and registered as a child after boot.
set -euo pipefail

mvn install -q

SDK_CP=$(cat boot/target/sdk-cp.txt)
APP_JAR=$(cat boot/target/app-cp.txt)
BOOT_CLASSES=$(pwd)/boot/target/classes

java \
  -Djava.system.class.loader=com.example.boot.AkkaSystemClassLoader \
  -cp "${SDK_CP}:${APP_JAR}:${BOOT_CLASSES}" \
  com.example.boot.SystemCLBootMain \
  boot/target/sdk-cp.txt \
  boot/target/runtime-cp.txt \
  boot/target/app-cp.txt
