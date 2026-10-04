# Local Dream ET — Desktop Build Guide (v3.0.0)

Local Dream ET 电脑版：纯 Win32 C 前端 + stable-diffusion.cpp 真引擎（CPU 出图）。
UI 对齐手机 Jetpack Compose 版（窄幅竖向窗口、CPU/NPU 双 Tab、搜索、模型卡片、
生成页、设置页），全程 UTF-8，三语（简体中文 / English / 繁體中文），深色/浅色主题。

- 版本：**3.0.0**（APP_CODE=5）
- 版权：Copyright (C) 2026 ET
- 引擎：leejet/stable-diffusion.cpp（MIT，保留原作者署名）

---

## 目录结构（本构建目录）

```
local-dream-desktop/
├── launcher.c            # 纯 Win32 Unicode 前端（唯一 C 源文件，~2000 行）
├── catalog.h             # 【构建期生成，勿手改】由 gen_catalog.py 生成
├── models.json           # 唯一事实源：52 模型 + 三语 UI + 9 采样器 + 全局锁定原因
├── tools/gen_catalog.py  # 构建期脚本：models.json -> catalog.h（纯 ASCII，\uXXXX 转义）
├── resource.rc           # 版本信息 / 图标（3.0.0 / ET）
├── installer.nsi         # NSIS per-user Unicode 安装脚本
├── app.ico
├── pkg/LocalDreamET/     # 真引擎与运行时（sd-cli/sd-server/*.dll，禁止重下）
│   ├── LocalDream-ET.exe # 本工程编译产物
│   ├── sd-cli.exe / sd-server.exe / stable-diffusion.dll / ggml*.dll / webp...
│   ├── models/ output/ tmp/ update/   # 运行时目录（空，自动创建）
│   └── 使用说明.txt       # UTF-8 BOM
├── LocalDream-ET.exe                      # 产物：64 位 GUI EXE
├── LocalDream-ET-Setup-3.0.0.exe          # 产物：NSIS 安装包
└── LocalDream-ET-Windows-Portable-3.0.0.zip # 产物：免安装包
```

## 工具链（交叉编译，Linux 沙箱内产出 Windows 产物）

- 编译器：`llvm-mingw-20260922-ucrt`（UCRT，glibc 已实测）
- NSIS：v3.08-2
- Python（仅构建期）：`/opt/python3.12/bin/python3`

> EXE 双击即用，运行时**不依赖** Python。Python 只在构建期把 models.json 编译成
> catalog.h；最终产物里没有任何 python 运行时。

## 构建步骤

```bash
BASE=/home/user/Doubao/chats/38445171688291330
TC=$BASE/.toolchain/llvm-mingw-20260922-ucrt-ubuntu-22.04-x86_64
NS=$BASE/.toolchain/nsis
cd $BASE/local-dream-desktop

# 1) 由事实源生成目录表 + 三语 i18n（纯 ASCII catalog.h）
/opt/python3.12/bin/python3 tools/gen_catalog.py
#   -> models=52 (cpu=37, npu=15, locked=15), ui keys=52, samplers=9

# 2) 资源
"$TC/bin/llvm-windres" -O coff resource.rc -o resource.o

# 3) 编译 64 位 GUI EXE（-municode 入口 wWinMain；-mwindows 无控制台）
"$TC/bin/x86_64-w64-mingw32-gcc" -O2 -municode -mwindows launcher.c resource.o \
    -o LocalDream-ET.exe \
    -lcomctl32 -lshlwapi -lwininet -lole32 -loleaut32 -lgdi32 -luser32 \
    -lshell32 -lcomdlg32 -luuid -lws2_32 -static-libgcc

# 4) 把 EXE 放进引擎目录
cp LocalDream-ET.exe pkg/LocalDreamET/LocalDream-ET.exe

# 5) NSIS 安装包（per-user，无需 UAC）
NSISDIR=$NS/usr/share/nsis "$NS/usr/bin/makensis" installer.nsi

# 6) 免安装包（整个 pkg/LocalDreamET 目录）
( cd pkg && zip -r -X -9 ../LocalDream-ET-Windows-Portable-3.0.0.zip LocalDreamET )
```

### UCRT 注意点（编译时踩过的坑）

- UCRT 的 `wcstok` 必须 **3 参**；本工程自封 `mywcstok(s, delim, &next)`。
- `WideCharToMultiByte` / `InternetConnectA` 在 UCRT 严格原型下必须传满全部参数
  （sizing 调用也补 `NULL,0,NULL,NULL`）。

## 模型目录与锁定策略

- **CPU Models（37 个）**：标准 SD1.5 safetensors/GGUF，HF 原仓 + ModelScope 国内镜像，
  可下载、可在本机 CPU 出图。列表与手机 `Model.kt` 的 initializeModels() 逐一对齐
  显示名/三语描述/默认提示词。
- **NPU Models（15 个，全部 🔒 锁定）**：Z-Image Turbo、FLUX.2 Klein、Qwen Image 2.1
  共 4 变体、Illustrious v16/dmd2、CyberRealistic v10/dmd2、以及 5 个 SD1.5 QNN 变体
  （anythingv5/qteamix/cuteyukimix/absolutereality/chilloutmix NPU 版）。这些依赖手机
  骁龙 NPU / QNN / MNN 的 `.so` 运行时，桌面 CPU ggml 引擎无法加载，故全部锁定并弹窗
  三语原因（zh/en/tw）：“电脑版当前为 CPU 引擎，该手机 NPU/QNN 专属模型暂不支持在电脑运行。”
  **不提供假下载/假生成。**

### 生成参数（对齐手机 GenerationDefaults.kt）

- 步数 1–50 默认 20；CFG 1–30 默认 7；采样器 9 种默认 `dpm`；种子空=随机；
  图生图 denoise 默认 0.45；比例 SD1.5 = 1:1 / 3:4 / 4:3。
- 采样器显示名在 `samplerToCli()` 内映射到 sd-cli 的 `dpm++` 记法；该 sd-cli build
  无独立 karras 调度器 token，karras 变体折叠为基方法（需 Windows 真机确认不报错）。

## UTF-8 / 乱码

- 源文件 UTF-8；WinINet 请求/响应按 UTF-8 编解码（CP_UTF8）；控制台 sd 输出正确解码；
- 安装器 `Unicode true`；`使用说明.txt` 存为 **UTF-8 with BOM**（记事本不乱码）；
- catalog.h 内所有非 ASCII 统一 `\uXXXX` 转义，保证编译器/工具链全程不碰非 ASCII 字节；
- 成品不得出现 `�`（U+FFFD）/ 锟斤拷。

## 安装布局

- 安装版（per-user，免 UAC）：`%LOCALAPPDATA%\Programs\Local Dream ET`
- 模型 / 输出 / 临时 / 更新：安装目录下 `models/ output/ tmp/ update/`（便携优先）。
- 若程序目录不可写（如 Program Files），模型自动回退 `%LOCALAPPDATA%\LocalDreamET`，
  并在设置页明示实际路径。

## 需 Windows 真机验收（沙箱无法端到端验证）

本沙箱为 Linux x86 / 4GB / 无 Windows / 无 GPU / 无 NPU，以下项**只做了静态构建验证**：

1. 真实出图（sd-cli 调起、采样进度、预览、保存 output/）。
2. Windows 下模型真实下载（ModelScope→HF 多镜像、断点续传、进度/速度/剩余时间）。
3. 电脑 NPU（沙箱无 NPU；NPU 标签页本就全部锁定，符合预期）。
4. 3 个预构建 CPU 模型（qteamixcpu / cuteyukimixcpu / chilloutmixcpu）的 HF
   safetensors URL 为 best-effort 映射，可能 404，需真机核对。
5. 采样器 karras 折叠后 sd-cli 是否接受。

报错时请回传：程序目录下 `tmp/` 内的 sd-cli 日志、`setup` 失败截图、以及
`设置 → 关于` 显示的版本号。
