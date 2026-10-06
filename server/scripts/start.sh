#!/usr/bin/env bash
# Local Dream ET Server 启动脚本 (Copyright (C) 2026 etc)
cd "$(dirname "$0")/.."
# headless 守护模式： nohup ./bin/etserver > logs/server.log 2>&1 &
exec ./bin/etserver
