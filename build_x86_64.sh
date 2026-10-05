#!/usr/bin/env bash
# Build the x86_64 native SD1.5 engine for pure x86_64 emulators/devices.
set -e

source "$HOME/.cargo/env"

CPP_DIR="$(cd "$(dirname "$0")" && pwd)/app/src/main/cpp"
NDK="${ANDROID_NDK_ROOT:-$HOME/android-sdk/ndk/29.0.14206865}"
QNN="${QNN_SDK_ROOT:-$HOME/qnnsdk/qairt/2.50.0.260828}"
BUILD="$CPP_DIR/build/android-x86_64"
TCBIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TCBIN/x86_64-linux-android21-clang"

echo "NDK=$NDK"

if [ ! -f "$BUILD/build.ninja" ]; then
  cmake -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=x86_64 \
    -DANDROID_PLATFORM=android-21 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DQNN_DEBUG_ENABLE=OFF \
    -DENABLE_QNN_NPU=OFF \
    -DQNN_SDK_ROOT="$QNN" \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.10 \
    -DCMAKE_ANDROID_ARCH_ABI=x86_64 \
    -S "$CPP_DIR" -B "$BUILD"
fi

cmake --build "$BUILD" --parallel 1

mkdir -p "$CPP_DIR/../jniLibs/x86_64"
# Output dir is usually bin/x86_64/; detect it.
SRC=$(ls "$BUILD"/bin/*/libstable_diffusion_core.so | head -1)
cp "$SRC" "$CPP_DIR/../jniLibs/x86_64/"

echo "X86_64_BUILD_DONE"
ls -lh "$CPP_DIR/../jniLibs/x86_64/libstable_diffusion_core.so"
file "$CPP_DIR/../jniLibs/x86_64/libstable_diffusion_core.so"
"$TCBIN/llvm-readelf" -h "$CPP_DIR/../jniLibs/x86_64/libstable_diffusion_core.so" | grep -E "Machine|Class"
