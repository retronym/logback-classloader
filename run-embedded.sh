#!/usr/bin/env bash
# True embedded-mode simulation: SDK + App on the actual system classloader.
# The agent rewrites Loader.getClassLoaderOfObject() to return the bridge CL.
set -euo pipefail

mvn install -q

SDK_CP=$(cat boot/target/sdk-cp.txt)
APP_JAR=$(cat boot/target/app-cp.txt)
AGENT_JAR=$(pwd)/agent/target/agent-1.0-SNAPSHOT.jar
BOOT_CLASSES=$(pwd)/boot/target/classes

# In true embedded mode SDK+App are on the system classpath (via -cp here).
# Only runtime.jar is isolated in a URLClassLoader built by EmbeddedBootMain.
java \
  -javaagent:"${AGENT_JAR}" \
  -cp "${SDK_CP}:${APP_JAR}:${BOOT_CLASSES}:${AGENT_JAR}" \
  com.example.boot.EmbeddedBootMain \
  boot/target/sdk-cp.txt \
  boot/target/runtime-cp.txt \
  boot/target/app-cp.txt
