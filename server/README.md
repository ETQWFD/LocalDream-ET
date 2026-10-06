# Local Dream ET Server v1.0.0

本地、离线、无需联网推理的 AI 出图 HTTP API 服务（Copyright (C) 2026 etc, MIT）。

单文件 C 外壳 `src/etserver.c` 负责：首次终端数字选模型、控制台显示
【服务地址 / 46 位 API Key / 模型名】、鉴权、反向代理、看门狗崩溃重启。
真正出图由 [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp)（MIT）
的 `sd-server` 完成；**本目录只含外壳源码，不含约 40MB 的引擎二进制与模型**。

## 构建
```bash
./build.sh
# Linux:  build/etserver
# Windows: build-win/etserver.exe  （需 llvm-mingw，见 win/mingw-toolchain.cmake）
```
引擎 `sd-server` 请按 stable-diffusion.cpp 官方说明自行编译：
- Linux：放入与 `etserver` 同目录 `bin/sd-server`
- Windows：`sd-server.exe` + `stable-diffusion.dll` + `ggml-*.dll` 与 `etserver.exe` 同目录

## 运行 / 接口
- 启动：`./scripts/start.sh` 或直接运行外壳；首启数字选模型并下载到 `models/`
- 健康（免 Key）：`GET /healthz`
- OpenAI 兼容：`POST /v1/images/generations`（`Authorization: Bearer <Key>`）
- sdapi 兼容：`POST /sdapi/v1/txt2img`
- 配置：`config/etserver.conf`（首启生成）；内容限制默认关闭（无限制），控制台 `f` 切换

## 发布包
Windows NSIS 安装程序脚本：`packaging/server.nsi`；预发布 tag：`server-v1.0.0`。
