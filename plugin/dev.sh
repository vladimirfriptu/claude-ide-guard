#!/usr/bin/env bash
# Convenience wrapper: sets JAVA_HOME to the keg-only JDK 21 (installed via
# `brew install openjdk@21`, not on PATH) and forwards all args to ./gradlew.
#
# Usage:
#   ./dev.sh runIde          # launch a sandbox IDE with the plugin
#   ./dev.sh buildPlugin     # build the installable .zip
#   ./dev.sh test            # run unit tests
set -euo pipefail

export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "JDK 21 not found at $JAVA_HOME" >&2
  echo "Install it with: brew install openjdk@21" >&2
  exit 1
fi

cd "$(dirname "$0")"
exec ./gradlew "$@"
