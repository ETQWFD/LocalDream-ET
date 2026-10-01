#!/usr/bin/env bash
# Build a pure CPU/GPU (MNN/OpenCL) native engine for 32-bit armeabi-v7a.
# QNN NPU is aarch64-only, so ENABLE_QNN_NPU=OFF: no QNN libs are copied or
# linked; the backend header/SampleApp code still compiles and simply has no
# NPU backend to dlopen at runtime on 32-bit devices.
set -e

# Rust toolchain (tokenizers-cpp ships a Rust static lib).
source "$HOME/.cargo/env"

CPP_DIR="$(cd "$(dirname "$0")" && pwd)/app/src/main/cpp"
NDK="${ANDROID_NDK_ROOT:-$HOME/android-sdk/ndk/25.2.9519653}"
QNN="${QNN_SDK_ROOT:-$HOME/qnnsdk/qairt/2.50.0.260828}"
BUILD="$CPP_DIR/build/android-armv7"
TCBIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

# Cargo needs an explicit linker for the Android targets; CMake already passes
# CC_/CXX_/AR_, but rustc resolves its linker through this env var.
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$TCBIN/armv7a-linux-androideabi21-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TCBIN/aarch64-linux-android21-clang"

echo "NDK=$NDK"
echo "QNN=$QNN"

# --- Compatibility shims ---------------------------------------------------
# 1) tokenizers-cpp names the NDK clang from the Rust target triple
#    (armv7-linux-androideabi), but the NDK ships the armv7a- prefix. Add
#    symlinks for the API levels we build with.
for api in 21 28; do
  ln -sf "armv7a-linux-androideabi${api}-clang" \
     "$TCBIN/armv7-linux-androideabi${api}-clang"
  ln -sf "armv7a-linux-androideabi${api}-clang++" \
     "$TCBIN/armv7-linux-androideabi${api}-clang++"
done

# 2) Newer rustc (>=1.80-era lint turned hard error) rejects two implicit
#    autorefs in tokenizers-c. Apply the bundled fix once (idempotent).
TOK_DIR="$CPP_DIR/3rdparty/tokenizers-cpp"
PATCH="$CPP_DIR/patches/tokenizers_armv7_rust198.patch"
if [ -f "$PATCH" ] && git -C "$TOK_DIR" apply --check "$PATCH" 2>/dev/null; then
  git -C "$TOK_DIR" apply "$PATCH"
  echo "applied tokenizers rust198 patch"
elif git -C "$TOK_DIR" apply --reverse --check "$PATCH" 2>/dev/null; then
  echo "tokenizers rust198 patch already applied"
fi
# --------------------------------------------------------------------------

if [ ! -f "$BUILD/build.ninja" ]; then
cmake -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=armeabi-v7a \
  -DANDROID_PLATFORM=android-21 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DQNN_DEBUG_ENABLE=OFF \
  -DENABLE_QNN_NPU=OFF \
  -DQNN_SDK_ROOT="$QNN" \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.10 \
  -DCMAKE_ANDROID_ARCH_ABI=armeabi-v7a \
  -S "$CPP_DIR" -B "$BUILD"
fi

cmake --build "$BUILD" --parallel 2

mkdir -p "$CPP_DIR/../jniLibs/armeabi-v7a"
cp "$BUILD/bin/armeabi-v7a/libstable_diffusion_core.so" \
   "$CPP_DIR/../jniLibs/armeabi-v7a/"

echo "ARMV7_BUILD_DONE"
ls -lh "$CPP_DIR/../jniLibs/armeabi-v7a/libstable_diffusion_core.so"
"$TCBIN/llvm-readelf" -h \
   "$CPP_DIR/../jniLibs/armeabi-v7a/libstable_diffusion_core.so" | grep -E "Machine|Class"
