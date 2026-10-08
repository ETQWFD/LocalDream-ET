// POST /api/online  body {id:"<客户端随机uuid>"}  心跳，写 online:<id>（TTL 120s）
// GET  /api/online  → {ok:true, online:N}  列出未过期心跳键数 = 当前在线
// 在线数真实来自后端 KV；过期键自动被 TTL 清除。函数未部署时前端显示 N/A。
import { json, corsHeaders, readBody } from "./_lib.js";

const PREFIX = "online:";
const TTL = 120; // 秒，客户端每 30s 上报一次

export async function onPost(context) {
  const kv = context.env.ET_KV;
  if (!kv) return json({ ok: false, error: "KV 未绑定" }, 500, context.request);
  const body = await readBody(context.request);
  let id = String(body.id || "").trim().replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 40);
  if (!id) id = "g" + Math.random().toString(36).slice(2, 12);
  try {
    await kv.put(PREFIX + id, String(Date.now()), { expirationTtl: TTL });
    return json({ ok: true, ttl: TTL }, 200, context.request);
  } catch (e) {
    return json({ ok: false, error: "心跳失败" }, 500, context.request);
  }
}

export async function onGet(context) {
  const kv = context.env.ET_KV;
  if (!kv) return json({ ok: false, error: "KV 未绑定" }, 500, context.request);
  try {
    let count = 0;
    let cursor = null;
    do {
      const opt = { prefix: PREFIX, limit: 100 };
      if (cursor) opt.cursor = cursor;
      const res = await kv.list(opt);
      count += (res.keys ? res.keys.length : 0);
      cursor = res.list_complete ? null : res.cursor;
    } while (cursor);
    return json({ ok: true, online: count }, 200, context.request);
  } catch (e) {
    return json({ ok: false, error: "读取失败" }, 500, context.request);
  }
}

export async function onRequest(context) {
  if (context.request.method === "OPTIONS") return new Response(null, { status: 204, headers: { ...corsHeaders(context.request) } });
  if (context.request.method === "POST") return onPost(context);
  if (context.request.method === "GET") return onGet(context);
  return json({ ok: false, error: "Method Not Allowed" }, 405, context.request);
}
