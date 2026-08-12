#!/usr/bin/env bash
set -euo pipefail
if [[ -x "$(dirname "$0")/gradle/wrapper/gradlew" ]]; then
  exec "$(dirname "$0")/gradle/wrapper/gradlew" "$@"
fi
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
echo "Gradle not found. Install a project-local wrapper in gradle/wrapper/gradlew or install gradle."
exit 1
