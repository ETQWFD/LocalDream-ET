# Local Dream ET Server (Go)

Go 重写版服务端：保留原来的 API Key / 局域网 IP 分发接口，额外带一个
**临时出图窗口**（`/admin`），可直接输入正向/反向提示词、步数、CFG、种子、
尺寸，调用本机已下载的 stable-diffusion.cpp `sd-server` 出图。

- `GET  /healthz`
- `POST /v1/images/generations`（OpenAI 兼容，Bearer Key）
- `POST /sdapi/v1/txt2img` / `POST /api/txt2img` / `POST /api/img2img`
- `GET  /admin` 出图窗口（本机管理页）
- 出图窗口与 API 共用同一个引擎，串行排队；HTTP 服务始终可响应，不会“未响应”。

## 运行
```bash
./etserver-go                 # 首次运行生成 config/server-go.json 和 API Key
./etserver-go -config config/server-go.json
```
把 `stable-diffusion.cpp` 的 `sd-server`（Windows 是 `sd-server.exe`）放到
`bin/`，模型放到 `models/`（`.safetensors` / `.gguf`），首次启动自动拉起引擎。

## 构建
```bash
GOOS=linux   GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o etserver-go-linux .
GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o etserver-go.exe .
```
