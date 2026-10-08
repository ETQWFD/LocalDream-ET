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
