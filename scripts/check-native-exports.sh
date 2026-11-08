#!/usr/bin/env bash
set -euo pipefail
project_root=$(cd -- "$(dirname -- "$0")/.." && pwd)
# Use LLVM_NM directly, or the caller's Android NDK. No local toolchain setup.
if [[ -n "${LLVM_NM:-}" ]]; then
  nm_bin="$LLVM_NM"
elif [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
  nm_bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/${NDK_HOST_TAG:-linux-x86_64}/bin/llvm-nm"
else
  nm_bin=llvm-nm
fi
for abi in arm64-v8a x86_64; do
  symbols=$("$nm_bin" --defined-only -D "$project_root/android/app/src/main/jniLibs/$abi/libgliff_android_core.so")
  for method in viewport openSession openSessionWithMode openSessionOnOutput pollEvent sendControl remoteAction setClipboard requestClipboard closeSession; do
    if ! grep -q " T Java_dev_omarchy_gliff_NativeCore_${method}$" <<< "$symbols"; then
      printf 'Missing JNI export: %s %s\n' "$abi" "$method" >&2
      exit 1
    fi
  done
  printf 'PASS: all expected JNI exports for %s\n' "$abi"
done
