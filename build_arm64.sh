#!/usr/bin/env bash
# Build the 64-bit arm64 native engine WITH QNN NPU runtime packaging,
# matching the shipped libstable_diffusion_core.so capabilities.
set -e

source "$HOME/.cargo/env"

CPP_DIR="$(cd "$(dirname "$0")" && pwd)/app/src/main/cpp"
NDK="${ANDROID_NDK_ROOT:-$HOME/android-sdk/ndk/29.0.14206865}"
QNN="${QNN_SDK_ROOT:-$HOME/qnnsdk/qairt/2.50.0.260828}"
BUILD="$CPP_DIR/build/android-arm64"
TCBIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TCBIN/aarch64-linux-android21-clang"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$TCBIN/armv7a-linux-androideabi21-clang"

echo "NDK=$NDK"; echo "QNN=$QNN"

if [ ! -f "$BUILD/build.ninja" ]; then
cmake -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-21 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DQNN_DEBUG_ENABLE=OFF \
  -DENABLE_QNN_NPU=OFF \
  -DQNN_SDK_ROOT="$QNN" \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.10 \
  -DCMAKE_ANDROID_ARCH_ABI=arm64-v8a \
  -S "$CPP_DIR" -B "$BUILD"
fi

cmake --build "$BUILD" --parallel 1

mkdir -p "$CPP_DIR/../jniLibs/arm64-v8a"
cp "$BUILD/bin/arm64-v8a/libstable_diffusion_core.so" \
   "$CPP_DIR/../jniLibs/arm64-v8a/"

echo "ARM64_BUILD_DONE"
ls -lh "$CPP_DIR/../jniLibs/arm64-v8a/libstable_diffusion_core.so"
"$TCBIN/llvm-readelf" -h \
   "$CPP_DIR/../jniLibs/arm64-v8a/libstable_diffusion_core.so" | grep -E "Machine|Class"
