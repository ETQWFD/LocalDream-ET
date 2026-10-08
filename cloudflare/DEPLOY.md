# 评分/评论写票网关 · 一键部署（Cloudflare Worker）

读侧（平均分/人数/分布/评论展示）已经可用：官网直接匿名读公开仓
`ETQWFD/LocalDream-ET-ratings`。本 Worker 只负责**公网写入/管理员删除**，
workers.dev 自带 HTTPS，可被 https://etc.tw.kg 跨域调用（无混合内容问题）。

逻辑已在本地对真实仓库端到端验证：多设备投票、指纹去重、聚合、评论违禁词/链接拦截、
管理员口令删除并重算、CORS。本文档只剩“填密钥 + 两条命令”。

## 1. 生成 GitHub 最小权限令牌（约 1 分钟）
登录 GitHub → Settings → Developer settings → **Fine-grained tokens** → Generate new token：
- Token name：`LocalDream-ratings-write`
- Expiration：最长（90 天，到期重复此步骤轮换）
- Repository access：**Only select repositories** → 仅勾选 `LocalDream-ET-ratings`
- Permissions → Repository permissions → **Contents = Read and write**（其余全部保持 No access/只读默认，Metadata 会自动只读）
- Generate token → 复制（形如 `github_pat_...`，只显示一次）

该令牌只能读写这一个公开仓库的文件，碰不到账号其它任何仓库。

## 2. 部署 Worker（约 2 分钟）
```bash
cd cloudflare
npm i -g wrangler          # 已安装可跳过
wrangler login             # 浏览器登录 Cloudflare（免费账号即可）
wrangler deploy            # 部署，输出 https://et-ratings.<你的子域>.workers.dev
wrangler secret put GITHUB_TOKEN   # 粘贴第 1 步的 github_pat_…
wrangler secret put ADMIN_KEY      # 自设一个后台删除口令
wrangler deploy            # 让密钥生效（再部署一次）
```

## 3. 接线官网
把部署得到的地址填入 `docs/index.html` 与 `docs/dan.html` 的写票常量
（当前为同源 `/api/rating`，未部署时自动降级、不显示假框）：
```js
var RATING_API = "https://et-ratings.<你的子域>.workers.dev";
```
提交推 main 即可。评分 GET、评论 GET 仍直读公开仓库；POST 走该 Worker。

## 端点
- `GET  /healthz` → `{ok, configured}`
- `GET  /rating`  → `{avg,count,highest,dist,updatedAt}`
- `POST /rating`  body `{score:1-5, fp:64hex}`；同指纹返回 `{voted:true,score:旧分}`
- `GET  /comments` / `POST /comments` body `{nick?,text,fp}`（链接/图片/脏词拒绝）
- `POST /admin` header `X-Admin-Key`，body `{action:delete_rating|delete_comment|reset_ratings,id}`

## 数据与口径
- 评分：`ratings/<sha256(fp)>.json = {score,country,prov,model,ua,ts}`，一票一设备。
- 评论：`comments/<id>.json = {id,nick,text,country,prov,model,ua,ts,status}`。
- 聚合：`summary.json`；删除评分后从现存票全量重算。
- 地区：Cloudflare `request.cf.country`（免费档稳定给到国家；regionCode/city 多为企业版能力，
  拿不到省份则省份留空，绝不编造）。机型：User-Agent 轻量解析。
- 限频：同 IP 评分 30 秒≤1、每天≤20；评论 15 秒≤1、每天≤30（存 `limits/`）。

## 安全
- `GITHUB_TOKEN`、`ADMIN_KEY` 只作为 Worker Secret，存在 Cloudflare，**不在任何仓库/前端**。
- 公网只能经受限端点写入，无法直接触达 GitHub API 或看到令牌。
- 轮换/吊销：GitHub fine-grained tokens 页 Regenerate/Revoke，再 `wrangler secret put`。
