// ============================================================
//  LocalDream ET · 评论/评分/在线 无服务器函数 公共库
//  运行环境：腾讯云 EdgeOne Pages Functions（同构可直接部署到
//  Cloudflare Pages Functions，绑定同名 KV 即可，无需改代码）。
//  所有密钥只从 context.env 读取，绝不出现在前端包。
// ============================================================

// 允许的前端来源（同源部署时 Origin 即本站；以下为兜底 CORS 白名单）
const ALLOWED_ORIGINS = [
  "https://etc.tw.kg",
  "http://localhost:8080",
  "http://127.0.0.1:8080",
];
// EdgeOne / Pages 域名一般是 https://<项目>.edgeone.app 或 .pages.dev，
// 同源部署时无需跨域；此处同时放行这些后缀。
function originAllowed(origin) {
  if (!origin) return true; // 非浏览器请求/curl 无 Origin，放行（同源）
  if (ALLOWED_ORIGINS.indexOf(origin) >= 0) return true;
  if (/\.edgeone\.app$/.test(new URL(origin).hostname)) return true;
  if (/\.pages\.dev$/.test(new URL(origin).hostname)) return true;
  return false;
}

export function corsHeaders(request) {
  const origin = request.headers.get("Origin") || "";
  const allowOrigin = originAllowed(origin) ? origin || "*" : ALLOWED_ORIGINS[0];
  return {
    "Access-Control-Allow-Origin": allowOrigin,
    "Access-Control-Allow-Methods": "GET,POST,DELETE,OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, X-Admin-Key",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin",
  };
}

export function json(data, status, request) {
  return new Response(JSON.stringify(data), {
    status: status || 200,
    headers: { "Content-Type": "application/json; charset=utf-8", ...corsHeaders(request) },
  });
}

// ---------- 内容安全 ----------
// 辱骂/脏词词库（可自行扩展；命中即拒绝保存）
export const BAD_WORDS = [
  "傻逼", "煞笔", "傻B", "sb", "草泥马", "尼玛", "他妈的", "他妈", "操你",
  "操你妈", "狗娘养", "滚蛋", "去死", "废物", "垃圾", "fuck", "shit",
  "bitch", "asshole", "nigger",
];

// URL / 裸域名 / 图片视频标识 / 脏词
const RE_URL = /https?:\/\/|ftp:\/\//i;
const RE_WWW = /\bwww\./i;
const RE_BARE_DOMAIN = /\b[a-z0-9][a-z0-9-]{1,62}\.(com|net|org|cn|io|cc|xyz|top|me|tv|info|app|dev|gg|lol|vip|club|site|online|fun|shop|store|tech|space|icu|pw|wang)\b/i;
const RE_MEDIA = /\.(png|jpe?g|gif|webp|svg|bmp|mp4|webm|mov|avi|mkv)($|\?)/i;
const RE_IMGTAG = /<img|image\/|\[img\]|!\[.*\]\(|data:image/i;

export function hasForbidden(text) {
  const t = String(text || "");
  if (RE_URL.test(t)) return "内容含链接地址，评论区禁止贴 URL";
  if (RE_WWW.test(t)) return "内容含 www 链接，评论区禁止贴链接";
  if (RE_BARE_DOMAIN.test(t)) return "内容疑似含裸域名，评论区禁止贴链接";
  if (RE_MEDIA.test(t) || RE_IMGTAG.test(t)) return "评论区禁止贴图片/视频";
  const lower = t.toLowerCase();
  for (const w of BAD_WORDS) {
    if (lower.indexOf(w.toLowerCase()) >= 0) return "内容含辱骂/违禁词，已被拒绝";
  }
  return null; // 未命中
}

// ---------- 客户端 IP / 匿名指纹 ----------
export function clientIp(request) {
  const xff = request.headers.get("x-forwarded-for") || "";
  if (xff) return xff.split(",")[0].trim();
  return request.headers.get("x-real-ip") || "0.0.0.0";
}

function fnv1a(s) {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return ("00000000" + (h >>> 0).toString(16)).slice(-8);
}
export function ipHash(ip) { return fnv1a(String(ip)); }

// ---------- 频率限制（基于 KV，key 带 TTL） ----------
// 命中则返回 {blocked:true, waitSec}；否则写入并放行。
export async function rateLimit(kv, key, minIntervalSec) {
  try {
    const last = await kv.get(key);
    const now = Math.floor(Date.now() / 1000);
    if (last) {
      const lt = parseInt(last, 10) || 0;
      if (now - lt < minIntervalSec) {
        return { blocked: true, waitSec: minIntervalSec - (now - lt) };
      }
    }
    await kv.put(key, String(now), { expirationTtl: Math.max(60, minIntervalSec * 4) });
    return { blocked: false };
  } catch (e) {
    return { blocked: false }; // KV 抖动不影响正常用户
  }
}

// 管理口令校验：只与 env.ADMIN_KEY 比对，不回显、不记日志
export function checkAdmin(request, env) {
  const got = request.headers.get("X-Admin-Key") || "";
  if (!env || !env.ADMIN_KEY) return false;
  if (got.length !== env.ADMIN_KEY.length) return false;
  // 简单恒定时间比较
  let diff = 0;
  for (let i = 0; i < got.length; i++) diff |= got.charCodeAt(i) ^ env.ADMIN_KEY.charCodeAt(i);
  return diff === 0;
}

export async function readBody(request) {
  try { return await request.json(); } catch (e) { return {}; }
}

// ---------- 客户端 IP（多平台候选） ----------
// 来源：EdgeOne Pages Functions request.eo（官方 https://edgeone.cloud.tencent.com/pages/document/162936866445025280 ）；
// Cloudflare Pages Functions 用 cf-connecting-ip / request.cf。
export function extractClientIp(request) {
  try { if (request.eo && request.eo.ip) return String(request.eo.ip); } catch (e) {}
  const cf = request.headers.get("cf-connecting-ip") || request.headers.get("x-real-ip") || "";
  if (cf) return cf.trim();
  const xff = request.headers.get("x-forwarded-for") || "";
  if (xff) return xff.split(",")[0].trim();
  return "0.0.0.0";
}

// ---------- 地理位置（服务端取，绝不编造） ----------
// 依次尝试：EdgeOne Pages 的 request.eo.geo（扁平与嵌套两种已知形状）、
// Cloudflare 的 request.cf、以及 cf-ipcountry 头。拿不到一律 "unknown"。
// 出处：
//  - EdgeOne Pages：request.eo.geo —— https://edgeone.cloud.tencent.com/pages/document/162936866445025280
//  - EdgeOne Makers 中间件 GeoProperties 扁平字段 countryName/countryCodeAlpha2/regionName/cityName
//    —— https://cloud.tencent.com/document/product/1552/127609
//  - 线上示例渲染出的嵌套 country{name,code} / region{region,code,city} —— https://functions-geolocation.edgeone.app/
//  - Cloudflare Pages Functions：request.cf.country / regionCode / city —— https://developers.cloudflare.com/workers/runtime-apis/request/
export function extractGeo(request) {
  const out = { country: "unknown", region: "" };
  try {
    const g = (request.eo && request.eo.geo) || request.eo || null;
    if (g) {
      // 扁平形状（Makers GeoProperties）
      if (g.countryCodeAlpha2 || g.countryName) {
        out.country = g.countryCodeAlpha2 || g.countryName || "unknown";
        out.region = g.regionName || g.cityName || "";
        return out;
      }
      // 嵌套形状（Pages 线上示例）
      if (g.country && typeof g.country === "object") {
        out.country = g.country.codeAlpha2 || g.country.code || g.country.name || "unknown";
      }
      if (g.region && typeof g.region === "object") {
        out.region = g.region.region || g.region.name || g.region.city || "";
      }
      if (out.country !== "unknown") return out;
    }
    // Cloudflare Pages Functions
    const cf = request.cf;
    if (cf && (cf.country || cf.regionCode)) {
      out.country = cf.country || "unknown";
      out.region = cf.regionCode || cf.city || "";
      return out;
    }
    // 头兜底
    const cc = request.headers.get("cf-ipcountry") || request.headers.get("x-geo-country") || "";
    if (cc && cc !== "XX") out.country = cc;
  } catch (e) {}
  return out;
}

// ---------- UA 轻量解析机型（纯函数，便于核对） ----------
export function parseUa(ua) {
  ua = String(ua || "");
  let m;
  // iPhone; CPU iPhone OS 17_2_1 like Mac OS X（先判 iPhone，避免落到 Mac）
  m = ua.match(/iPhone;\s*CPU iPhone OS\s*([\d_]+)/i);
  if (m) return "iPhone (iOS " + m[1].replace(/_/g, ".") + ")";
  if (/iPad/.test(ua)) return "iPad";
  // Android 13; ...; M2101K7AG Build/...
  m = ua.match(/Android\s+([\d.]+)[^;]*;\s*([^;)\s]+?)\s*Build\//i);
  if (m) return "Android " + m[1] + " · " + m[2].replace(/_/g, " ");
  if (/Windows NT/.test(ua)) {
    const br = /Edg\//.test(ua) ? "Edge" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "";
    return "Windows PC" + (br ? " · " + br : "");
  }
  if (/Mac OS X|Macintosh/.test(ua)) return "Mac PC";
  if (/Linux/.test(ua)) return "Linux PC";
  return "未知设备";
}

export function todayKey() {
  return new Date().toISOString().slice(0, 10); // YYYY-MM-DD UTC
}

// ---------- GitHub Contents API（写票/删票，持有 GITHUB_TOKEN） ----------
// 存储仓库：ETQWFD/LocalDream-ET-ratings（public，匿名可读）。令牌只从 env.GITHUB_TOKEN 读。
export const GH_REPO = "ETQWFD/LocalDream-ET-ratings";
export const GH_API = "https://api.github.com/repos/" + GH_REPO;

function ghHeaders(env, extra) {
  const h = Object.assign({ "Accept": "application/vnd.github+json", "User-Agent": "LocalDream-ET-Rating" }, extra || {});
  if (env && env.GITHUB_TOKEN) h["Authorization"] = "Bearer " + env.GITHUB_TOKEN;
  return h;
}
export function b64encodeUnicode(str) { return btoa(unescape(encodeURIComponent(str))); }
export function b64decodeUnicode(b64) { return decodeURIComponent(escape(atob(b64))); }

// GET 文件，返回 Response（调用方判断 status）
export function ghGet(env, path) {
  return fetch(GH_API + path, { headers: ghHeaders(env) });
}
// PUT（新建或更新，自动带现有 sha 做乐观更新）
export async function ghPut(env, path, contentObj, message) {
  let sha = null;
  const ex = await fetch(GH_API + path, { headers: ghHeaders(env) });
  if (ex.ok) { try { sha = (await ex.json()).sha; } catch (e) {} }
  const body = { message: message || ("upd " + path), content: b64encodeUnicode(JSON.stringify(contentObj)) };
  if (sha) body.sha = sha;
  return fetch(GH_API + path, { method: "PUT", headers: ghHeaders(env, { "Content-Type": "application/json" }), body: JSON.stringify(body) });
}
// DELETE（需先取 sha）
export async function ghDelete(env, path) {
  const ex = await fetch(GH_API + path, { headers: ghHeaders(env) });
  if (!ex.ok) return ex;
  let sha = null; try { sha = (await ex.json()).sha; } catch (e) {}
  return fetch(GH_API + path, { method: "DELETE", headers: ghHeaders(env, { "Content-Type": "application/json" }), body: JSON.stringify({ message: "del " + path, sha }) });
}
// 读取一个文件并解析 JSON（404 返回 null）
export async function ghGetJSON(env, path) {
  const r = await fetch(GH_API + path, { headers: ghHeaders(env) });
  if (r.status === 404) return null;
  if (!r.ok) throw new Error("GH " + r.status);
  const j = await r.json();
  try { return j.content ? JSON.parse(b64decodeUnicode(j.content)) : j; } catch (e) { return j; }
}
// 列目录（数组），过滤 .gitkeep / 非 json
export async function ghList(env, dir) {
  const r = await fetch(GH_API + dir, { headers: ghHeaders(env) });
  if (!r.ok) return [];
  const arr = await r.json();
  return Array.isArray(arr) ? arr.filter((f) => /\.json$/.test(f.name)) : [];
}


