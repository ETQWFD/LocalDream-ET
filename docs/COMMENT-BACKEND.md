# 评论 / 评分 / 在线数 后端部署说明（COMMENT-BACKEND）

> 本文件由 et.46「最后一舞」随站提供。**当前沙箱内没有任何已登录的 serverless CLI
> （已探测：`edgeone` / `wrangler` / `vercel` / `netlify` 均未安装或未登录），
> 因此函数尚未真正上线。** 下列源码已写好、可一键部署；在你完成部署前，
> 官网评论区/评分与后台在线数会诚实显示“服务尚未部署 / N/A”，不会出现能填不能存的假输入框。

## 0. 实际采用的方案

- **运行时**：腾讯云 **EdgeOne Pages Functions**（首选；免费额度足够个人站）。
  本目录代码为 Web 标准 ESM，**同构可直接搬到 Cloudflare Pages Functions**（见第 4 节），无需改逻辑。
- **存储**：**Edge KV（边缘键值）**，不依赖 GitHub Issues、不需要把 PAT 放进前端。
  - `comments:list`：评论数组 JSON，最多保留最新 200 条。
  - `rating:meta`：`{sum, count}`，服务端实时聚合平均分。
  - `online:<id>`：后台在线心跳，**TTL 120 秒**自动过期，进后台每 30s 上报一次。
- **跨设备持久化**：评论/评分存在边缘 KV，任何设备刷新、换设备都读到同一份数据，**不是 localStorage 假历史**。
- **密钥**：`ADMIN_KEY` 只配置在函数运行环境变量里，**绝不出现在前端 JS / 仓库 / 包内**；
  前台 `dan.html` 在页面里临时输入后随请求头 `X-Admin-Key` 发给函数比对。

## 1. 端点清单（全部同源 `/api/*`）

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/comments` | 拉取评论列表 `{ok, comments:[{id,nick,text,ts}]}` |
| POST | `/api/comments` | 提交 `{nick,text}`，服务端校验后写 KV |
| GET | `/api/rating` | 聚合 `{ok, avg, count}`（avg 保留 1 位小数，0–5） |
| POST | `/api/rating` | 提交 `{score:1..5}`，服务端累加 sum/count |
| POST | `/api/online` | 后台心跳 `{id}`，写 `online:<id>`（TTL 120s） |
| GET | `/api/online` | 列出未过期心跳键数 `{ok, online:N}` |
| POST | `/api/admin` | `X-Admin-Key` 校验后：`{action:"delete_comment",id}` 删评论 / `{action:"reset_rating"}` 清零评分 |

## 2. 服务端安全实现位置（源码在 `docs/functions/api/`）

- **拒绝 URL / 裸域名 / www**：`_lib.js` 的 `RE_URL / RE_WWW / RE_BARE_DOMAIN`。
- **拒绝图片/视频标识**：`_lib.js` 的 `RE_MEDIA / RE_IMGTAG`。
- **辱骂违禁词词库**：`_lib.js` 的 `BAD_WORDS`（含“傻逼/他妈的/fuck/shit…”，可自行扩充）。
  命中即 **400 拒绝保存**，前台不可见。
- **长度限制**：昵称 ≤20、内容 ≤300（`comments.js`）。
- **频率限制**：同 IP 评论 15s 一条、评分 30s 一条（`_lib.js` 的 `rateLimit`，KV 记时间戳）。
- **管理口令**：`_lib.js` 的 `checkAdmin`，恒定时间比对 `env.ADMIN_KEY`；普通用户无法删改他人数据。

## 3. 一键部署：EdgeOne Pages

1. 把本仓库导入 **腾讯云 EdgeOne Pages**（关联 GitHub 仓库）。
   - 构建命令留空；**输出根目录填 `docs/`**（与 GitHub Pages 同一目录）。
   - 这样 `docs/functions/api/*` 会被识别为边缘函数，路由到 `/api/*`。
2. 新建一个 **Edge KV 命名空间**，在项目「函数 → 环境变量/绑定」里：
   - 绑定名 **`ET_KV`** → 选刚建的 KV 命名空间。
   - 环境变量 **`ADMIN_KEY`** = 你自己设一串强口令（仅服务端可见）。
3. 部署后访问 `https://<你的项目>.edgeone.app/api/rating`，返回 `{"ok":true,...}` 即成功。
4. **域名**：在 EdgeOne Pages 绑定自定义域 `etc.tw.kg`（DNS 按 EdgeOne 提示改 CNAME）。
   - 绑定后官网与 `/api/*` **同源**，前端相对路径直接可用，无需 CORS 特例。
   - 若你仍想把静态页留在 GitHub Pages、函数单独托管，则把函数部署到独立函数域名，
     并在 `index.html`/`dan.html` 的前端脚本里设置 `window.ET_API_BASE="https://函数域名"`；
     本函数已对 `.edgeone.app` / `.pages.dev` / `https://etc.tw.kg` 放行 CORS。

## 4. 迁移到 Cloudflare Pages Functions（等价方案）

- 把 `docs/functions/` 整个放到 Cloudflare 项目的 `functions/` 目录（构建输出根仍指向 `docs/` 内容）。
- 建一个 **KV namespace**，绑定变量名同样叫 **`ET_KV`**。
- 在项目 Settings → Environment variables 加 **`ADMIN_KEY`**。
- Cloudflare Pages Functions 的 `onGet/onPost/onRequest` 签名与 KV API（get/put/list）与本代码一致，**无需改动**。

## 5. 部署后如何确认“真持久化”

- A 设备发一条评论 → B 设备浏览器打开 `/api/comments` 能看到同一条。
- 连续打两次评分 → `/api/rating` 的 `count` 与 `avg` 随之变化（非写死）。
- 关掉后台页面 2 分钟后再查 `/api/online`，该心跳消失（TTL 生效）。
- 不带 `X-Admin-Key` 调 `/api/admin` → 401。

> 在你（有账号的人）完成上述部署前，以上端点不存在；前端已做诚实降级，不会伪造任何评论/评分/在线数。
