# 自编译原生引擎（libstable_diffusion_core.so）

低配机「后端启动失败 / 根本出不了图」的根因之一是：包里的 `armeabi-v7a` 引擎长期是
2025-11-10 的预编译产物，不含 et.244 才加进源码的 **Vulkan → OpenCL → CPU 逐级降级**
（旧 so 里连 `MNN_GPU_POLICY` 字符串都搜不到）。所以 32 位必须自己编。
`app/src/main/jniLibs/**/*.so` 被 `.gitignore` 排除（产物太大），**不在仓库里**，
本文件记录的命令就是唯一的复现路径；两颗成品同时存档在对应 Release 的资产里。

## 环境

- NDK：`/home/user/Android/Sdk/ndk/27.2.12479018`
- Rust：`~/.cargo/bin`（**必须先进 PATH**，tokenizers 的构建命令里是裸 `cargo`；
  没带上会报一句毫无线索的 `No such file or directory`）
- CMake + Ninja

```bash
export PATH="$HOME/.cargo/bin:$PATH"
cd app/src/main/cpp
```

## 配置（两个必须传的参数，都是踩过的坑）

1. **必须传 `-DCMAKE_TOOLCHAIN_FILE`**。漏了不会立刻报错，而是 MNN 子工程改用宿主
   `/usr/bin/g++` 编译，然后在 `CMakeLists.txt` 里那句 Clang 专有的
   `-Wno-error=for-loop-analysis` 上直接 `cc1plus: error` 终止。
2. **即使 `ENABLE_QNN_NPU=OFF` 也必须传 `-DQNN_SDK_ROOT`**。配置阶段有一句无条件
   `file(COPY ${QNN_SDK_ROOT}/examples/... SampleApp ...)`，不传就
   `CMake Error: ... QnnSampleApp.cpp: No such file or directory`。

arm64（高配，骁龙 8+ / 天玑 9000+ 等）：

```bash
cmake -S . -B build/local-arm64 -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DMNN_OPENCL=ON -DMNN_VULKAN=ON -DMNN_ARM82=ON \
  -DENABLE_QNN_NPU=OFF -DQNN_SDK_ROOT=/home/user/qnnsdk/qairt/2.50.0.260828
ninja -C build/local-arm64 -j2 libstable_diffusion_core.so
```

armeabi-v7a（低配；`MNN_ARM82` 是 armv8.2 特性，32 位必须关）：

```bash
cmake -S . -B build/local-armv7a -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DMNN_OPENCL=ON -DMNN_VULKAN=ON -DMNN_ARM82=OFF \
  -DENABLE_QNN_NPU=OFF -DQNN_SDK_ROOT=/home/user/qnnsdk/qairt/2.50.0.260828
ninja -C build/local-armv7a -j2 libstable_diffusion_core.so
```

产物在 **`build/<dir>/bin/<abi>/libstable_diffusion_core.so`**（不是 build 根目录），
拷到 `app/src/main/jniLibs/<abi>/` 后随 APK 打包。

## 32 位特有的一个上游 bug（已在配置期自动绕开）

子模块 `3rdparty/tokenizers-cpp/CMakeLists.txt` 用 **cargo 三元组**去拼 NDK 的 clang
包装器名：得到 `armv7-linux-androideabi<api>-clang`，而 NDK 里的真名是
`armv7a-linux-androideabi<api>-clang`（多一个 `a`）。arm64 两个名字恰好一致，所以这个
bug 只在 32 位暴露：`onig_sys` 的 cc-rs 阶段报
`failed to find tool ".../armv7-linux-androideabi24-clang"` 并终止整条 32 位构建。

子模块内容不会被本仓库提交，所以修复方式是**由我们的 `CMakeLists.txt` 在配置期为 NDK
补一个别名符号链接**（`armeabi-v7a` 时才做，幂等）。配置日志里会看到
`32 位：已为 NDK clang 包装器补上 cargo 三元组别名 ...`。

## 为什么没有 32 位的 NPU

高通 QNN / Hexagon 的运行库只提供 arm64，带这种 NPU 的机型本身也都是 64 位，32 位包
不可能用 NPU；联发科没有公开的 NPU SDK（需其自签 NDA）。因此 32 位路线只做
MNN 的 OpenCL / Vulkan / CPU，靠逐级降级保证「一定出得来图」。

## 校验 GPU 后端是否真的进了库（重要）

引擎链接带 `-Wl,-s`，直接对成品 `strings` 判断会失真。正确做法是取链接命令、
去掉 `-Wl,-s`、换个输出路径重链一份再查符号：

```bash
ninja -C build/local-armv7a -t commands libstable_diffusion_core.so | tail -1 \
  | sed -e 's/-Wl,-s//' -e 's#-o [^ ]*libstable_diffusion_core.so#-o /tmp/verify.so#' | bash
nm -C /tmp/verify.so | grep -cE 'VulkanRuntimeCreator|OpenCLRuntimeCreator|InitVulkanOnce'
```

MNN 的后端靠 `MNNInsertExtraRuntimeCreator` + 文件内 `static bool gResistor` 自注册，
静态归档链接时会被整段丢弃 —— 所以 `app/src/main/cpp/CMakeLists.txt` 里对 MNN 用了
`-Wl,--whole-archive $<TARGET_FILE:MNN> -Wl,--no-whole-archive`。没有这段的话，
so 能编出来、也能跑，但只会走 CPU，看起来就是「GPU 加速没生效」。
