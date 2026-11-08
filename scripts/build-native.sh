#!/usr/bin/env bash
set -euo pipefail
project_root=$(cd -- "$(dirname -- "$0")/.." && pwd)
source "$project_root/scripts/local-env.sh"
cd "$project_root"
targets=("$@")
if [[ ${#targets[@]} -eq 0 ]]; then targets=(aarch64-linux-android x86_64-linux-android); fi
for target in "${targets[@]}"; do
  case "$target" in
    aarch64-linux-android) abi=arm64-v8a; linker=aarch64-linux-android29-clang ;;
    x86_64-linux-android) abi=x86_64; linker=x86_64-linux-android29-clang ;;
    *) echo "Unsupported Android Rust target: $target" >&2; exit 1 ;;
  esac
  linker_var="CARGO_TARGET_${target^^}_LINKER"
  linker_var=${linker_var//-/_}
  export "$linker_var=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/$linker"
  cc_var="CC_${target//-/_}"
  ar_var="AR_${target//-/_}"
  export "$cc_var=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/$linker"
  export "$ar_var=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
  cargo build --locked ${CARGO_OFFLINE:+--offline} --release --target "$target" -p gliff-android-core
  mkdir -p "android/app/src/main/jniLibs/$abi"
  cp "target/$target/release/libgliff_android_core.so" "android/app/src/main/jniLibs/$abi/"
done
