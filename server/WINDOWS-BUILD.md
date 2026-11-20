# Nightmare Road-ET Server · Windows 出包清单（②→①→③）

> 这台云主机没有 MSVC / mingw，以下步骤在装有 Visual Studio 2022（含“使用 C++ 的桌面开发”）的 Windows 上执行。

## ② 允许使用 API Key（本次已改源码）
`src/etserver.c` 新增 `IDC_APIKEY(1014)` 复选框，绑 `g_cfg.enable_api_key`，点击即 `save_config()`：
- 勾选 = 校验 `Authorization: Bearer <key>`（401 拒绝）；
- 不勾 = 任何人可调用（默认）。

编译：`cd server && build-win.bat`（或 `cl /O2 /utf-8 src\etserver.c win\etserver.res /link winhttp.lib ws2_32.lib shell32.lib user32.lib gdi32.lib /OUT:build-win\etserver.exe`）

## ① 引擎（当前缺失 → 503 engine not ready）
1. 拉 `stable-diffusion.cpp`（Windows 分支/主线均可），CMake：
   `cmake -S . -B build -G "Visual Studio 17 2022" -A x64 -DSD_BUILD_SHARED=OFF`
   `cmake --build build --config Release --target sd-server`
2. 产物 `build/Release/sd-server.exe` 放到安装目录（与 `etserver.exe` 同级），CPU 版即可跑；有 NVIDIA 再 `-DSD_CUDA=ON`。
3. 模型用 gguf/safetensors（anythingV5），路径写 `config/etserver.conf` 的 `model_path`。
4. `server.nsi` 的 `File` 段加 `sd-server.exe` 与所需 dll，安装后不再“引擎缺失”。
5. 启动探测：`g_progdir` 找 `sd-server.exe` → 缺失则读配置 `engine_path` → 仍缺失才报错；未就绪时前端按钮置灰并显示“正在加载模型”。

## ③ 临时出租窗口（待定规则）
实现前需一条明确规则，建议默认值（可直接采纳）：
- 按小时出租，生成一次性 `api_key`（46 位），绑定 `port`，到期自动 `kill_engine` + 删除 key；
- 出租期间强制 `enable_api_key=1`；
- 窗口显示：剩余时间、端口、一键复制地址、立即结束。

## 打包
`makensis packaging\server.nsi` → 安装包；同时把 `docs/server-update.json` 的版本号与 sha256 更新为新 exe。
