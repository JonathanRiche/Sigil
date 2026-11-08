#!/usr/bin/env bash
set -euo pipefail
project_root=$(cd -- "$(dirname -- "$0")/.." && pwd)
source "$project_root/scripts/local-env.sh"
: "${ANDROID_HOME:?Set ANDROID_HOME to an existing Android SDK with platform 36 and build-tools 35.0.0}"
: "${GRADLE_BIN:?Set GRADLE_BIN to an existing Gradle 8.11.1 executable}"
export GRADLE_USER_HOME="$project_root/.cache/gradle"
export TMPDIR="$project_root/.cache/tmp"
mkdir -p "$TMPDIR" "$project_root/.cache/java-home"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.io.tmpdir=$TMPDIR -Duser.home=$project_root/.cache/java-home"
tasks=("$@")
if [[ ${#tasks[@]} -eq 0 ]]; then tasks=(:app:assembleDebug); fi
exec "$GRADLE_BIN" -p "$project_root/android" ${GRADLE_OFFLINE:+--offline} --no-daemon --console plain "${tasks[@]}"
