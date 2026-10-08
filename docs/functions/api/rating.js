// GET  /api/rating  → {ok:true, avg:4.7, count:N, sum:..}
// POST /api/rating  body {score:1..5}  服务端聚合平均分与人数，不写死
import { json, corsHeaders, clientIp, ipHash, rateLimit, readBody } from "./_lib.js";

const K = "rating:meta"; // {sum:number, count:number}

export async function onGet(context) {
  const kv = context.env.ET_KV;
  if (!kv) return json({ ok: false, error: "KV 未绑定" }, 500, context.request);
  try {
    const raw = await kv.get(K);
    const m = raw ? JSON.parse(raw) : { sum: 0, count: 0 };
    const count = Number(m.count) || 0;
    const sum = Number(m.sum) || 0;
    const avg = count > 0 ? Math.round((sum / count) * 10) / 10 : 0;
    return json({ ok: true, avg, count, sum }, 200, context.request);
  } catch (e) {
    return json({ ok: false, error: "读取失败" }, 500, context.request);
  }
}

export async function onPost(context) {
  const kv = context.env.ET_KV;
  if (!kv) return json({ ok: false, error: "KV 未绑定" }, 500, context.request);
  const body = await readBody(context.request);
  const score = Math.round(Number(body.score));
  if (!(score >= 1 && score <= 5)) return json({ ok: false, error: "评分须为 1–5" }, 400, context.request);

  const ip = clientIp(context.request);
  const rl = await rateLimit(kv, "rl:rt:" + ipHash(ip), 30); // 同 IP 30 秒内只能打一次
  if (rl.blocked) return json({ ok: false, error: "评分太快，请稍后再试" }, 429, context.request);

  let m = { sum: 0, count: 0 };
  try { const raw = await kv.get(K); if (raw) m = JSON.parse(raw); } catch (e) {}
  m.sum = (Number(m.sum) || 0) + score;
  m.count = (Number(m.count) || 0) + 1;
  try { await kv.put(K, JSON.stringify(m)); } catch (e) {
    return json({ ok: false, error: "保存失败" }, 500, context.request);
  }
  const avg = Math.round((m.sum / m.count) * 10) / 10;
  return json({ ok: true, avg, count: m.count, sum: m.sum }, 200, context.request);
}

export async function onRequest(context) {
  if (context.request.method === "OPTIONS") return new Response(null, { status: 204, headers: { ...corsHeaders(context.request) } });
  if (context.request.method === "GET") return onGet(context);
  if (context.request.method === "POST") return onPost(context);
  return json({ ok: false, error: "Method Not Allowed" }, 405, context.request);
}
