# Local Dream ET Server v1.1.1 (Linux x86_64)

本地离线 AI 出图 API 服务。首次运行在终端按数字选择并下载模型到 models/，
随后控制台显示【服务地址 / 46 位 API Key / 模型名】，可复制到任意兼容工具调用。

启动：  ./scripts/start.sh   （或 ./bin/etserver）
健康：  GET /healthz
OpenAI：POST /v1/images/generations   （Authorization: Bearer <key>）
sdapi： POST /sdapi/v1/txt2img
配置：  config/etserver.conf（首次启动生成）  版权所有 (C) 2026 etc（MIT）
