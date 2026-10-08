// ============================================================
//  LocalDream ET · 评分 + 评论 公网写票网关（Cloudflare Worker）
//  存储：公开仓库 ETQWFD/LocalDream-ET-ratings（Contents API）
//  前端：https://etc.tw.kg 跨域 POST 到本 Worker（workers.dev 自带 HTTPS）
//  密钥：只从 env 读（wrangler secret put）：
//    GITHUB_TOKEN  fine-grained PAT，仅该仓 Contents Read/Write
//    ADMIN_KEY     后台删除/清零口令
//  安全：绝不打印/回显令牌；公网请求无法直接触达 GitHub API。
// ============================================================

export const ALLOWED_ORIGINS = [
  "https://etc.tw.kg",
  "http://localhost:8080",
  "http://127.0.0.1:8080",
];

const GH_REPO = "ETQWFD/LocalDream-ET-ratings";
const GH_API = "https://api.github.com/repos/" + GH_REPO;

// ---------------- 纯函数（可单测） ----------------

export function originAllowed(origin) {
  if (!origin) return true; // curl / 同源无 Origin
  if (ALLOWED_ORIGINS.indexOf(origin) >= 0) return true;
  try {
    const h = new URL(origin).hostname;
    if (h === "etc.tw.kg" || h.endsWith(".pages.dev")) return true;
  } catch (e) {}
  return false;
}

export function corsHeaders(request) {
  const origin = request.headers.get("Origin") || "";
  return {
    "Access-Control-Allow-Origin": originAllowed(origin) ? origin || "*" : ALLOWED_ORIGINS[0],
    "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, X-Admin-Key",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin",
  };
}

export const BAD_WORDS = [
  "傻逼", "煞笔", "傻b", "草泥马", "尼玛", "他妈的", "他妈", "操你",
  "狗娘养", "滚蛋", "去死", "废物", "fuck", "shit", "bitch", "asshole", "nigger",
];
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
  const low = t.toLowerCase();
  for (const w of BAD_WORDS) if (low.indexOf(w) >= 0) return "内容含辱骂/违禁词，已被拒绝";
  return null;
}

export function clientIp(request) {
  const cf = request.headers.get("cf-connecting-ip") || request.headers.get("x-real-ip") || "";
  if (cf) return cf.trim();
  const xff = request.headers.get("x-forwarded-for") || "";
  return xff ? xff.split(",")[0].trim() : "0.0.0.0";
}

// Cloudflare 免费档 request.cf 稳定提供 country(ISO)；regionCode/city 多为企业版能力。
// 拿不到省份只记国家，绝不编造。
export function extractGeo(request) {
  const out = { country: "unknown", prov: "" };
  try {
    const cf = request.cf || {};
    if (cf.country && cf.country !== "XX") out.country = String(cf.country);
    out.prov = String(cf.regionCode || cf.region || cf.city || "");
    const cc = request.headers.get("cf-ipcountry");
    if (out.country === "unknown" && cc && cc !== "XX") out.country = cc;
  } catch (e) {}
  return out;
}

export function parseUa(ua) {
  ua = String(ua || "");
  let m;
  m = ua.match(/iPhone;\s*CPU iPhone OS\s*([\d_]+)/i);
  if (m) return "iPhone (iOS " + m[1].replace(/_/g, ".") + ")";
  if (/iPad/.test(ua)) return "iPad";
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

export function sanitizeFp(fp) {
  fp = String(fp || "").toLowerCase().replace(/[^a-f0-9]/g, "");
  return /^[a-f0-9]{64}$/.test(fp) ? fp : "";
}

function fnv1a(s) {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 0x01000193); }
  return ("00000000" + (h >>> 0).toString(16)).slice(-8);
}
export const ipHash = (ip) => fnv1a(String(ip));
export const todayKey = () => new Date().toISOString().slice(0, 10);

export function emptySummary() {
  return { avg: 0, count: 0, highest: 0, dist: { "1": 0, "2": 0, "3": 0, "4": 0, "5": 0 }, updatedAt: null };
}

export function checkAdmin(request, env) {
  const got = request.headers.get("X-Admin-Key") || "";
  if (!env || !env.ADMIN_KEY || !got) return false;
  if (got.length !== env.ADMIN_KEY.length) return false;
  let diff = 0;
  for (let i = 0; i < got.length; i++) diff |= got.charCodeAt(i) ^ env.ADMIN_KEY.charCodeAt(i);
  return diff === 0;
}

// ---------------- GitHub Contents API（持有最小权限令牌） ----------------

function ghHeaders(env, extra) {
  return Object.assign(
    { Accept: "application/vnd.github+json", "User-Agent": "LocalDream-ET-Rating" },
    env && env.GITHUB_TOKEN ? { Authorization: "Bearer " + env.GITHUB_TOKEN } : {},
    extra || {}
  );
}
const b64enc = (s) => btoa(unescape(encodeURIComponent(s)));
const b64dec = (b) => decodeURIComponent(escape(atob(b)));

async function ghGetJSON(env, path) {
  const r = await fetch(GH_API + path, { headers: ghHeaders(env) });
  if (r.status === 404) return null;
  if (!r.ok) throw new Error("GH GET " + path + " " + r.status);
  const j = await r.json();
  return j.content ? JSON.parse(b64dec(j.content)) : j;
}
async function ghList(env, dir) {
  const r = await fetch(GH_API + dir, { headers: ghHeaders(env) });
  if (!r.ok) return [];
  const arr = await r.json();
  return Array.isArray(arr) ? arr.filter((f) => /\.json$/.test(f.name)) : [];
}
// 读-改-写，带 sha；遇 409 冲突重试一次（并发投票）
async function ghUpdateFile(env, path, mutate, message) {
  for (let attempt = 0; attempt < 2; attempt++) {
    let sha = null, cur = null;
    const ex = await fetch(GH_API + path, { headers: ghHeaders(env) });
    if (ex.status === 404) { cur = null; }
    else if (ex.ok) { const j = await ex.json(); sha = j.sha; cur = j.content ? JSON.parse(b64dec(j.content)) : null; }
    else throw new Error("GH pre " + path + " " + ex.status);
    const next = await mutate(cur);
    const body = { message, content: b64enc(JSON.stringify(next)) };
    if (sha) body.sha = sha;
    const w = await fetch(GH_API + path, {
      method: "PUT", headers: ghHeaders(env, { "Content-Type": "application/json" }), body: JSON.stringify(body),
    });
    if (w.ok) return next;
    if (w.status === 409 && attempt === 0) continue; // 重试
    throw new Error("GH PUT " + path + " " + w.status);
  }
}
async function ghDelete(env, path, message) {
  const ex = await fetch(GH_API + path, { headers: ghHeaders(env) });
  if (ex.status === 404) return true;
  if (!ex.ok) throw new Error("GH pre-del " + ex.status);
  const { sha } = await ex.json();
  const d = await fetch(GH_API + path, {
    method: "DELETE", headers: ghHeaders(env, { "Content-Type": "application/json" }),
    body: JSON.stringify({ message: message || "del", sha }),
  });
  return d.ok;
}

// ---------------- 业务 ----------------

async function recomputeSummary(env) {
  const files = await ghList(env, "/contents/ratings");
  let count = 0, sum = 0, highest = 0;
  const dist = emptySummary().dist;
  for (const f of files) {
    try {
      const v = await ghGetJSON(env, "/contents/ratings/" + f.name);
      const sc = Math.round(Number(v && v.score));
      if (sc >= 1 && sc <= 5) { count++; sum += sc; highest = Math.max(highest, sc); dist[String(sc)]++; }
    } catch (e) {}
  }
  return { avg: count ? Math.round((sum / count) * 10) / 10 : 0, count, highest, dist, updatedAt: new Date().toISOString() };
}

async function handleRatingPost(request, env) {
  if (!env.GITHUB_TOKEN) return json(request, { ok: false, error: "写票服务未配置 GITHUB_TOKEN" }, 500);
  const body = await request.json().catch(() => ({}));
  const score = Math.round(Number(body.score));
  if (!(score >= 1 && score <= 5)) return json(request, { ok: false, error: "评分须为 1–5 整数" }, 400);
  const fp = sanitizeFp(body.fp);
  if (!fp) return json(request, { ok: false, error: "缺少合法设备指纹" }, 400);

  const existing = await ghGetJSON(env, "/contents/ratings/" + fp + ".json");
  if (existing) return json(request, { ok: true, voted: true, score: existing.score });

  const ip = clientIp(request);
  const rlPath = "/contents/limits/rate-" + ipHash(ip) + "-" + todayKey() + ".json";
  let stamps = [];
  try { stamps = (await ghGetJSON(env, rlPath)) || []; } catch (e) {}
  const now = Date.now();
  stamps = stamps.filter((t) => now - Number(t) < 86400000);
  if (stamps.length && now - stamps[stamps.length - 1] < 30000)
    return json(request, { ok: false, error: "评分太快，请稍后再试" }, 429);
  if (stamps.length >= 20)
    return json(request, { ok: false, error: "今日该网络评分已达上限，感谢参与" }, 429);
  stamps.push(now);

  const geo = extractGeo(request);
  const ua = request.headers.get("user-agent") || "";
  const item = { score, country: geo.country, prov: geo.prov, model: parseUa(ua), ua, ts: now };

  await ghUpdateFile(env, "/contents/ratings/" + fp + ".json", async () => item, "vote " + score);
  await ghUpdateFile(env, rlPath, async () => stamps, "rate-limit").catch(() => {});
  const s = await ghUpdateFile(env, "/contents/summary.json", async (cur) => {
    const base = cur || emptySummary();
    const count = (base.count || 0) + 1;
    const sum = (base.avg || 0) * (base.count || 0) + score;
    const dist = Object.assign(emptySummary().dist, base.dist || {});
    dist[String(score)] = (dist[String(score)] || 0) + 1;
    return {
      avg: Math.round((sum / count) * 10) / 10, count,
      highest: Math.max(base.highest || 0, score), dist, updatedAt: new Date().toISOString(),
    };
  }, "summary n+1");

  return json(request, { ok: true, voted: false, avg: s.avg, count: s.count, dist: s.dist, highest: s.highest });
}

async function handleCommentsGet(request, env) {
  const files = await ghList(env, "/contents/comments");
  const out = [];
  for (const f of files) {
    try {
      const c = await ghGetJSON(env, "/contents/comments/" + f.name);
      if (c && c.status !== "blocked") out.push({ nick: c.nick, text: c.text, country: c.country, prov: c.prov, model: c.model, ts: c.ts });
    } catch (e) {}
  }
  out.sort((a, b) => (b.ts || 0) - (a.ts || 0));
  return json(request, { ok: true, comments: out.slice(0, 200) });
}

async function handleCommentPost(request, env) {
  if (!env.GITHUB_TOKEN) return json(request, { ok: false, error: "评论服务未配置 GITHUB_TOKEN" }, 500);
  const body = await request.json().catch(() => ({}));
  const nick = String(body.nick || "").trim().slice(0, 20) || "匿名用户";
  const text = String(body.text || "").trim().slice(0, 300);
  const fp = sanitizeFp(body.fp);
  if (!fp) return json(request, { ok: false, error: "缺少合法设备指纹" }, 400);
  if (text.length < 2) return json(request, { ok: false, error: "评论内容太短" }, 400);
  const bad = hasForbidden(nick) || hasForbidden(text);
  if (bad) return json(request, { ok: false, error: bad }, 400);

  const ip = clientIp(request);
  const rlPath = "/contents/limits/cmt-" + ipHash(ip) + "-" + todayKey() + ".json";
  let stamps = [];
  try { stamps = (await ghGetJSON(env, rlPath)) || []; } catch (e) {}
  const now = Date.now();
  stamps = stamps.filter((t) => now - Number(t) < 86400000);
  if (stamps.length && now - stamps[stamps.length - 1] < 15000)
    return json(request, { ok: false, error: "评论太快，请稍后再试" }, 429);
  if (stamps.length >= 30) return json(request, { ok: false, error: "今日该网络评论已达上限" }, 429);
  stamps.push(now);

  const geo = extractGeo(request);
  const ua = request.headers.get("user-agent") || "";
  const id = now.toString(36) + "-" + fp.slice(0, 8);
  const rec = { id, nick, text, country: geo.country, prov: geo.prov, model: parseUa(ua), ua, ts: now, status: "ok" };
  await ghUpdateFile(env, "/contents/comments/" + id + ".json", async () => rec, "comment");
  await ghUpdateFile(env, rlPath, async () => stamps, "cmt-rate").catch(() => {});
  return json(request, { ok: true, id });
}

async function handleAdmin(request, env) {
  if (!checkAdmin(request, env)) return json(request, { ok: false, error: "管理员口令错误" }, 401);
  const body = await request.json().catch(() => ({}));
  if (body.action === "delete_rating") {
    await ghDelete(env, "/contents/ratings/" + sanitizeFp(body.id) + ".json", "admin del rating");
    const s = await recomputeSummary(env);
    await ghUpdateFile(env, "/contents/summary.json", async () => s, "admin recompute");
    return json(request, { ok: true, summary: s });
  }
  if (body.action === "delete_comment") {
    const id = String(body.id || "").replace(/[^a-z0-9-]/gi, "");
    await ghDelete(env, "/contents/comments/" + id + ".json", "admin del comment");
    return json(request, { ok: true });
  }
  if (body.action === "reset_ratings") {
    for (const f of await ghList(env, "/contents/ratings")) await ghDelete(env, "/contents/ratings/" + f.name, "reset").catch(() => {});
    const z = emptySummary();
    await ghUpdateFile(env, "/contents/summary.json", async () => z, "admin reset");
    return json(request, { ok: true, summary: z });
  }
  return json(request, { ok: false, error: "未知操作" }, 400);
}

function json(request, data, status) {
  return new Response(JSON.stringify(data), {
    status: status || 200,
    headers: Object.assign({ "Content-Type": "application/json; charset=utf-8" }, corsHeaders(request)),
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS")
      return new Response(null, { status: 204, headers: corsHeaders(request) });
    try {
      if (url.pathname === "/healthz")
        return json(request, { ok: true, configured: !!env.GITHUB_TOKEN });
      if (url.pathname === "/rating" && request.method === "GET")
        return json(request, { ok: true, ...((await ghGetJSON(env, "/contents/summary.json")) || emptySummary()) });
      if (url.pathname === "/rating" && request.method === "POST")
        return handleRatingPost(request, env);
      if (url.pathname === "/comments" && request.method === "GET")
        return handleCommentsGet(request, env);
      if (url.pathname === "/comments" && request.method === "POST")
        return handleCommentPost(request, env);
      if (url.pathname === "/admin" && request.method === "POST")
        return handleAdmin(request, env);
      return json(request, { ok: false, error: "Not Found" }, 404);
    } catch (e) {
      return json(request, { ok: false, error: "服务异常" }, 500);
    }
  },
};
