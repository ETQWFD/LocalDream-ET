// GET  /api/comments  → {ok:true, comments:[{id,nick,text,ts}]}
// POST /api/comments  body {nick, text}  匿名、服务端校验、持久化到 Edge KV
import { json, corsHeaders, hasForbidden, clientIp, ipHash, rateLimit, readBody } from "./_lib.js";

const K = "comments:list";
const MAX = 200; // 最多保留最新 200 条
const NICK_MAX = 20, TEXT_MAX = 300;

export async function onGet(context) {
  const kv = context.env.ET_KV;
  if (!kv) return json({ ok: false, error: "KV 未绑定" }, 500, context.request);
  try {
    const raw = await kv.get(K);
    const list = raw ? JSON.parse(raw) : [];
    return json({ ok: true, comments: Array.isArray(list) ? list : [] }, 200, context.request);
  } catch (e) {
    return json({ ok: false, error: "读取失败" }, 500, context.request);
  }
}

export async function onPost(context) {
  const kv = context.env.ET_KV;
  if (!kv) return json({ ok: false, error: "KV 未绑定" }, 500, context.request);
  const body = await readBody(context.request);
  let nick = String(body.nick || "").trim().slice(0, NICK_MAX);
  const text = String(body.text || "").trim().slice(0, TEXT_MAX);
  if (!nick) nick = "匿名网友" + Math.floor(1000 + Math.random() * 9000);

  if (!text) return json({ ok: false, error: "评论内容为空" }, 400, context.request);
  if (text.length > TEXT_MAX) return json({ ok: false, error: "评论过长（≤300字）" }, 400, context.request);

  const bad = hasForbidden(nick + " " + text);
  if (bad) return json({ ok: false, error: bad }, 400, context.request);

  // 频率限制：同 IP 15 秒一条
  const ip = clientIp(context.request);
  const rl = await rateLimit(kv, "rl:cm:" + ipHash(ip), 15);
  if (rl.blocked) return json({ ok: false, error: "发言太快，请 " + rl.waitSec + " 秒后再试" }, 429, context.request);

  const list = [];
  try {
    const raw = await kv.get(K);
    if (raw) { const arr = JSON.parse(raw); if (Array.isArray(arr)) list.push(...arr); }
  } catch (e) {}
  const comment = {
    id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
    nick, text, ts: Date.now(),
  };
  list.unshift(comment);
  while (list.length > MAX) list.pop();
  try {
    await kv.put(K, JSON.stringify(list));
  } catch (e) {
    return json({ ok: false, error: "保存失败" }, 500, context.request);
  }
  return json({ ok: true, comment }, 200, context.request);
}

export async function onRequest(context) {
  if (context.request.method === "OPTIONS") return new Response(null, { status: 204, headers: { ...corsHeaders(context.request) } });
  if (context.request.method === "GET") return onGet(context);
  if (context.request.method === "POST") return onPost(context);
  return json({ ok: false, error: "Method Not Allowed" }, 405, context.request);
}
