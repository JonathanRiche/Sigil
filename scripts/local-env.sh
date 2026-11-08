#!/usr/bin/env bash
# Source this file to keep all mutable build and emulator state in the project.
project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export JAVA_HOME="${JAVA_HOME:-$project_root/.cache/toolchains/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$project_root/.cache/toolchains/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/30.0.16248370}"
export GRADLE_BIN="${GRADLE_BIN:-$project_root/.cache/toolchains/gradle-8.11.1/bin/gradle}"
export RUSTUP_HOME="$project_root/.cache/toolchains/rustup"
export RUSTUP_TOOLCHAIN=1.95.0
export CARGO_HOME="$project_root/.cache/cargo"
export CARGO_TARGET_DIR="$project_root/target"
export GRADLE_USER_HOME="$project_root/.cache/gradle"
export GLIFF_LOCAL_HOME="$project_root/.cache/home"
export TMPDIR="$project_root/.cache/tmp"
export ANDROID_USER_HOME="$project_root/.cache/android-user"
export ANDROID_AVD_HOME="$project_root/.cache/avd"
export ADB_SERVER_SOCKET=tcp:127.0.0.1:5038
export XDG_CACHE_HOME="$project_root/.cache/xdg"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$GLIFF_LOCAL_HOME" "$TMPDIR" "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME" "$XDG_CACHE_HOME"
