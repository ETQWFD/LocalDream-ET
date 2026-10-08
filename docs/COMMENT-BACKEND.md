# 评论 / 评分 后端部署说明（GitHub 仓库存储版）

> 存储不再使用 Edge KV。**读 = 公开仓库匿名直读（已上线，立即可用）**；**写 = 一个无服务器函数持有令牌写仓库（需用户部署）**。
> 本运行时无已登录的 EdgeOne / Cloudflare CLI，函数未替你部署；下列代码已写好、可一键上线。

## 0. 存储位置（已由作者建好）
- 公开仓库：`ETQWFD/LocalDream-ET-ratings`（public，分支 main）。
- `summary.json`：`{avg, count, highest, dist:{"1".."5"}, updatedAt}`，raw 直链
  `https://raw.githubusercontent.com/ETQWFD/LocalDream-ET-ratings/main/summary.json`（已实测 `Access-Control-Allow-Origin: *`、max-age=300）。
- 每票：`ratings/<fpHash>.json` = `{score, country, prov, model, ua, ts}`；评论：`comments/<id>.json` = `{nick, text, ts}`。
- 列目录：`https://api.github.com/repos/ETQWFD/LocalDream-ET-ratings/contents/ratings`（数组，过滤 `.gitkeep`）。
- **跨设备持久化**：所有读写都在这个公开仓库，任何设备刷新即见同一份数据，不是 localStorage。

## 1. 端点（函数代码在 docs/functions/api/）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/rating` | 代理仓库 summary（前端通常直读 raw，此端点保留兼容） |
| POST | `/api/rating` | body `{score:1..5, fp:sha256hex}`：幂等写票 + 读改写 summary |
| GET | `/api/comments` | 列 comments/（前端通常直读仓库） |
| POST | `/api/comments` | body `{nick,text}`：违禁词/URL/媒体校验后写 comments/ |
| POST | `/api/admin` | `X-Admin-Key` 校验后：`delete_comment` / `reset_rating` |
| GET/POST | `/api/admin/ratings` | 列票（可筛 score/region/device）；`{action:"delete",id}` 删票后从目录**重算** summary |

## 2. 数据模型与限频
- 指纹：前端 `localStorage` 随机种子 + 屏幕宽高×色深 + 时区 + `hardwareConcurrency` + `maxTouchPoints` + UA，做 **SHA-256**（64 hex）。后端 `ratings/<fp>.json` 已存在即幂等返回旧分，不新增。
- **边界**：清浏览器缓存 / 换浏览器 / 换设备会生成新指纹、被视为新设备（这是匿名投票的固有取舍，前端已在评分区小字说明）。
- 限频：`limits/rate-<sha(ip)>-<日期>.json` 存当日时间戳数组；同 IP 30s 内≤1 票、当日≤20 票，超限返回中文 429。评论 `limits/cmt-<sha(ip)>.json` 记最后时间戳，15s 内≤1 条。
- 聚合：投票时按 `avg = (旧avg×旧count + score)/(旧count+1)` 增量更新 `dist[score]++`、`count++`、`highest=max`；管理员删票后**从目录现存票全量重算** summary（不做易错的增减），保证聚合与明细一致。

## 3. 服务端采集（geo / UA）
- IP：依次读 `cf-connecting-ip` → `x-real-ip` → `x-forwarded-for` 首段。
- 地区（函数在边缘平台取，不编造）：
  - **Cloudflare Pages Functions**：`request.cf.country` / `request.cf.regionCode` / `request.cf.city`，或响应头 `cf-ipcountry`。
    出处：https://developers.cloudflare.com/workers/runtime-apis/request/
  - **EdgeOne Pages Functions**：`request.eo.geo`。出处：https://edgeone.cloud.tencent.com/pages/document/162936866445025280
    （扁平字段 `countryCodeAlpha2/regionName/cityName` 见 Makers 中间件 GeoProperties：https://cloud.tencent.com/document/product/1552/127609 ；嵌套示例 https://functions-geolocation.edgeone.app/ ）
  - 拿不到省份只记国家，再没有记 `unknown`。
- UA 机型（纯函数 `parseUa`）：Android 取 `; <Model> Build/`；iPhone/iPad 识别标识；PC 记系统+浏览器；否则“未知设备”。

## 4. 用户在控制台要做的精确动作
1. 建一个 **fine-grained Personal Access Token**：
   - Repository access → 仅选 `ETQWFD/LocalDream-ET-ratings`；
   - Permissions → **Contents: Read and write**、Metadata: Read-only。
2. 部署 `docs/functions/` 到 **EdgeOne Pages**（首选）或 **Cloudflare Pages Functions**：
   - 构建输出目录 = `docs/`（这样 `/functions/api/*` 路由到 `/api/*`）；
   - 环境变量两个：`GITHUB_TOKEN` = 上面的细粒度令牌（**只在服务端，绝不进前端**）；`ADMIN_KEY` = 你自设的管理口令（dan.html 临时输入，不落本地存储）。
3. 绑域：把 `etc.tw.kg` 迁到 EdgeOne Pages / Cloudflare Pages（**GitHub Pages 本身不跑函数**），静态页与 `/api/*` 同源。若静态页仍留 GitHub Pages，则函数单独部署到函数域名并在 `index.html`/`dan.html` 设 `window.ET_API_BASE="https://函数域名"`；函数已放行 `https://etc.tw.kg` 与 `.edgeone.app` / `.pages.dev` 的 CORS。

## 5. 前后端降级（诚实）
- 评分数字 / 评论列表：前端**直读公开仓库**，写函数没部署也照样真实展示。
- 写票（点星、发表评论）：初始化探测 `GET /api/rating`，不可达/404 时，禁用打分与提交按钮，显示“评分/评论提交服务维护中（写票后端待部署，见本文件）”——不会出现点了没反应、也不只存 localStorage。
- 管理员删票/删评论：需口令 + 函数在线；dan.html 接不上时显示后端未部署。
- 在线数：仓库版未做心跳写放大，未部署后端时 dan.html 在线数显 **N/A**（不假造）。
