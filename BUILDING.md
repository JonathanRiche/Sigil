# Building Sigil

These instructions target an x86_64 Linux build host. No Gradle wrapper is currently included. Use your own installed toolchains; generated caches, native libraries and APKs are not source dependencies.

## Prerequisites

- Rust 1.95 or a compatible newer toolchain, with `aarch64-linux-android` and `x86_64-linux-android` targets installed.
- JDK 17 and Gradle 8.11.1.
- Android SDK platform 36, build-tools 35.0.0 and NDK 30.0.16248370.
- Network access for pinned Cargo and Gradle dependency resolution on an empty cache.

The Gradle project uses AGP 8.10.1, Kotlin 2.1.20, AndroidX Window 1.5.1 and AndroidX Core 1.15.0. Dependency versions and Android settings are in the source manifests. Exact SDK/NDK versions are currently required by the build configuration.

## Native libraries

From the repository root, set the paths to your installed SDK and JDK. Set GRADLE_BIN to your Gradle executable for the next step. These commands do not depend on the development workspace's copied toolchains.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
export GRADLE_BIN=/path/to/gradle-8.11.1/bin/gradle
export PATH="$JAVA_HOME/bin:$PATH"
export CARGO_HOME="$PWD/.cache/cargo"
export CARGO_TARGET_DIR="$PWD/target"
export GRADLE_USER_HOME="$PWD/.cache/gradle"

for target in aarch64-linux-android x86_64-linux-android; do
  case "$target" in
    aarch64-linux-android) abi=arm64-v8a; compiler=aarch64-linux-android29-clang ;;
    x86_64-linux-android) abi=x86_64; compiler=x86_64-linux-android29-clang ;;
  esac
  ndk_bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
  linker_var="CARGO_TARGET_${target^^}_LINKER"
  linker_var=${linker_var//-/_}
  export "$linker_var=$ndk_bin/$compiler"
  export "CC_${target//-/_}=$ndk_bin/$compiler"
  export "AR_${target//-/_}=$ndk_bin/llvm-ar"
  cargo build --locked --release --target "$target" -p gliff-android-core
  mkdir -p "android/app/src/main/jniLibs/$abi"
  cp "target/$target/release/libgliff_android_core.so" \
    "android/app/src/main/jniLibs/$abi/"
done
```

The NDK compiler targets API 29, while the app minimum is API 30. This command block requires Bash. A macOS/ARM build host needs a different NDK prebuilt host path and has not been validated.

## APK

```sh
"$GRADLE_BIN" -p android --no-daemon --console plain :app:assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`. This is a debug-signed preview. `assembleRelease` has no configured release signing identity; it does not establish an installable signed release. Do not publish a local debug keystore as a release key.

Package ID is currently `dev.omarchy.gliff`; the app label is Sigil. The current version is `0.1.0-preview.3` (version code 4). Renaming the package would create a different installed app and would not automatically migrate remembered host trust or imported keys.

## Existing local helper scripts

`scripts/build-native.sh` and `scripts/build-android.sh` source `scripts/local-env.sh`, which forces project-local Rustup/Cargo state and assumes previously provisioned toolchains beneath `.cache/toolchains`. They are convenient for that environment but do not provision a fresh checkout. `scripts/provision-local.sh` copies explicitly supplied existing installations; it is not a general public installer. The manual build above avoids those assumptions.

Current JNI requires ten exports: viewport, openSession, openSessionWithMode, openSessionOnOutput, pollEvent, sendControl, remoteAction, setClipboard, requestClipboard and closeSession. Run `scripts/check-native-exports.sh` after copying both native libraries. It uses `ANDROID_NDK_HOME`, an explicit `LLVM_NM` executable, or `llvm-nm` on PATH; `NDK_HOST_TAG` can override the Linux x86_64 NDK host directory.

No build or device-validation claim is implied by these instructions. The portable manual procedure has been reviewed against the current manifests but has not been executed on a clean external machine.
