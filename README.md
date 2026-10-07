<div align="center">

# Local Dream ET  <img src="./docs/icon.png" width="32" alt="Local Dream ET">

**安卓本地 Stable Diffusion · 骁龙 NPU / CPU / GPU 加速**
### ET 定制版（开发者：ET）

</div>
新增

- **设置内「检测更新」**：应用读取本仓库 GitHub Pages 上的 `update.json` 比对版本号。
- **应用内直接下载**：发现新版本后在 App 内下载并显示百分比 / 已下载大小。
- **下载完直接安装**：首次自动申请 `REQUEST_INSTALL_PACKAGES`（“允许安装未知应用”），
  授权后立即调起**系统打包安装程序（Package Installer）**覆盖安装，全程不经过文件管理器或任何应用商店。
- **品牌**：应用名 *Local Dream ET*，独立包名 `io.github.etqwfd.localdreamet`，设置页显示开发者与版本。
- **32 位**：`abiFilters` 增加 `armeabi-v7a`（仅 CPU/GPU，说明见下文）。

## 官网与下载

- 官网（GitHub Pages）：<https://etqwfd.github.io/LocalDream-ET/>
- 更新清单：<https://etqwfd.github.io/LocalDream-ET/update.json>
- 最新安装包：见 [Releases](https://github.com/ETQWFD/LocalDream-ET/releases)，资产固定名 `LocalDream-ET.apk`。

## 关于原生引擎与 32 位（重要）

本仓库是 Android（Kotlin/Jetpack Compose）外壳 + 原生推理后端（C++）。
原生后端 `libstable_diffusion_core.so`、`libdit_engine.so` 以及高通 QNN 库**不属于源码仓库**，
需要用 NDK + 高通 QNN SDK 通过 `app/src/main/cpp/build.sh` 单独编译后放入：

- `app/src/main/jniLibs/<abi>/libstable_diffusion_core.so`
- `app/src/main/jniLibs/<abi>/libdit_engine.so`
- `app/src/main/assets/qnnlibs/`、`app/src/main/assets/ditlibs/`（QNN / DiT 运行库）

**架构说明：**

| ABI | 位数 | CPU/GPU(MNN/OpenCL) | NPU(QNN) |
| --- | --- | --- | --- |
| `arm64-v8a` | 64 位 | 支持 | 支持（骁龙，详见上游说明） |
| `armeabi-v7a` | 32 位 | 支持（需用 NDK 交叉编译 v7a 引擎） | 不支持（QNN 仅 aarch64） |

高通 QNN 只提供 `aarch64` 库，因此 **32 位设备只能走 CPU/GPU 引擎**；
发布的 arm64 安装包可直接使用，而可在 32 位设备上出图的 v7a 引擎需在装有
Android NDK（如 r28）与 Qualcomm QNN SDK 2.50 的 Linux 主机上执行交叉编译
（`ANDROID_ABI=armeabi-v7a`），本仓库已在 Gradle 中开启该 ABI 打包位。
大模型（尤其 SDXL）内存需求高，32 位设备建议使用 SD1.5。

## 从源码构建

需要 JDK 17、Android SDK（compileSdk 37）。原生引擎按上节单独构建。

```bash
# 拉取子模块
git submodule update --init --recursive

# 只编译 APK（jniLibs/assets 中需已存在对应引擎，否则仅界面可用）
./gradlew :app:assembleBasicRelease
```

Release 签名在 `app/release-keystore.properties` 中配置（已被 .gitignore 忽略）：

```properties
RELEASE_STORE_FILE=../et-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

> 自更新要求“已安装版本”和“更新包”使用**同一签名**，否则系统会拒绝覆盖安装。

## 更新机制工作原理

1. 设置 → **检测更新** → `AppUpdater.check()` 拉取 `update.json`（失败则回退到 GitHub Release API）。
2. `versionCode` 更高时弹窗显示新版本说明与大小。
3. 点“下载并安装”→ `AppUpdater.download()` 流式下载到缓存并回调进度。
4. 若无安装权限 → 跳转系统授权页，返回后继续。
5. `FileProvider` 授予安装器读取权限，`ACTION_VIEW` + `application/vnd.android.package-archive`
   直接打开系统打包安装程序。

发布新版本时：上传新 APK 到 Release（命名 `LocalDream-ET.apk`），
并更新 `docs/update.json` 的 `versionCode / versionName / releaseNotes`。



<div align="center">定制版 © ET</div>
