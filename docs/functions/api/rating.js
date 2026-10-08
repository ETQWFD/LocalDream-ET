// GET  /api/rating            → 代理公开仓库 summary.json（前端通常直读 raw）
// POST /api/rating  {score, fp}  写票到 ETQWFD/LocalDream-ET-ratings：
//   ratings/<fp>.json 一票，幂等；再读-改-写 summary.json（avg/count/dist/highest/updatedAt）。
import {
  json, corsHeaders, readBody, extractClientIp, extractGeo, parseUa,
  ipHash, ghGetJSON, ghPut, todayKey,
} from "./_lib.js";

function sanitizeFp(fp) {
  fp = String(fp || "").toLowerCase().replace(/[^a-f0-9]/g, "");
  return /^[a-f0-9]{64}$/.test(fp) ? fp : "";
}
function emptySummary() {
  return { avg: 0, count: 0, highest: 0, dist: { "1": 0, "2": 0, "3": 0, "4": 0, "5": 0 }, updatedAt: null };
}

export async function onGet(context) {
  try {
    const s = await ghGetJSON(context.env, "contents/summary.json") || emptySummary();
    return json({ ok: true, ...s }, 200, context.request);
  } catch (e) {
    return json({ ok: false, error: "读取失败：" + e.message }, 500, context.request);
  }
}

export async function onPost(context) {
  const env = context.env;
  if (!env.GITHUB_TOKEN) return json({ ok: false, error: "写票服务未配置 GITHUB_TOKEN" }, 500, context.request);
  const body = await readBody(context.request);
  const score = Math.round(Number(body.score));
  if (!(score >= 1 && score <= 5)) return json({ ok: false, error: "评分须为 1–5 整数" }, 400, context.request);
  const fp = sanitizeFp(body.fp);
  if (!fp) return json({ ok: false, error: "缺少合法设备指纹（64 位 sha256）" }, 400, context.request);

  // 1) 幂等：该设备已有票则直接返回
  const existing = await ghGetJSON(env, "contents/ratings/" + fp + ".json");
  if (existing) return json({ ok: true, voted: true, score: existing.score }, 200, context.request);

  // 2) 限频：limits/rate-<iphash>-<date>.json = 当日时间戳数组
  const ip = extractClientIp(context.request);
  const dayKey = todayKey();
  const rlPath = "contents/limits/rate-" + ipHash(ip) + "-" + dayKey + ".json";
  let stamps = [];
  try { stamps = (await ghGetJSON(env, rlPath)) || []; } catch (e) {}
  const now = Date.now();
  stamps = stamps.filter((t) => (now - Number(t)) < 86400 * 1000);
  const last = stamps.length ? stamps[stamps.length - 1] : 0;
  if (now - last < 30000) return json({ ok: false, error: "评分太快，请稍后再试" }, 429, context.request);
  if (stamps.length >= 20) return json({ ok: false, error: "今日该网络评分已达上限，感谢参与" }, 429, context.request);
  stamps.push(now);

  // 3) 服务端采集地区 + UA 机型
  const geo = extractGeo(context.request);
  const ua = context.request.headers.get("user-agent") || "";
  const item = { score, country: geo.country, prov: geo.region || "", model: parseUa(ua), ua, ts: now };

  // 4) 写票 + 写限频 + 读改写 summary
  await ghPut(env, "contents/ratings/" + fp + ".json", item, "vote " + score);
  await ghPut(env, rlPath, stamps, "rate-limit");

  let s;
  try { s = (await ghGetJSON(env, "contents/summary.json")) || emptySummary(); } catch (e) { s = emptySummary(); }
  const count = (s.count || 0) + 1;
  const sum = (s.avg || 0) * (s.count || 0) + score;
  s.avg = Math.round((sum / count) * 10) / 10;
  s.count = count;
  s.highest = Math.max(s.highest || 0, score);
  s.dist = Object.assign(emptySummary().dist, s.dist || {});
  s.dist[String(score)] = (s.dist[String(score)] || 0) + 1;
  s.updatedAt = new Date().toISOString();
  await ghPut(env, "contents/summary.json", s, "summary -> avg " + s.avg + " n " + count);

  return json({ ok: true, voted: false, avg: s.avg, count: s.count, dist: s.dist }, 200, context.request);
}

export async function onRequest(context) {
  if (context.request.method === "OPTIONS") return new Response(null, { status: 204, headers: { ...corsHeaders(context.request) } });
  if (context.request.method === "GET") return onGet(context);
  if (context.request.method === "POST") return onPost(context);
  return json({ ok: false, error: "Method Not Allowed" }, 405, context.request);
}
