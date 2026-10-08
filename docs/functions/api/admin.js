// POST /api/admin  body {action:"delete_comment"|"reset_rating", id?}
// 必须带请求头 X-Admin-Key，与环境变量 ADMIN_KEY 完全一致才放行。
// ADMIN_KEY 只存在于函数运行环境变量，绝不写进前端仓库。
import { json, corsHeaders, checkAdmin, readBody } from "./_lib.js";

export async function onPost(context) {
  const kv = context.env.ET_KV;
  if (!kv) return json({ ok: false, error: "KV 未绑定" }, 500, context.request);
  if (!checkAdmin(context.request, context.env)) {
    return json({ ok: false, error: "管理口令无效或未配置 ADMIN_KEY" }, 401, context.request);
  }
  const body = await readBody(context.request);
  const action = body.action;

  if (action === "delete_comment") {
    const id = String(body.id || "");
    if (!id) return json({ ok: false, error: "缺少评论 id" }, 400, context.request);
    let list = [];
    try { const raw = await kv.get("comments:list"); if (raw) list = JSON.parse(raw); } catch (e) {}
    const before = list.length;
    list = list.filter(function (c) { return String(c.id) !== id; });
    await kv.put("comments:list", JSON.stringify(list));
    return json({ ok: true, removed: before - list.length }, 200, context.request);
  }

  if (action === "reset_rating") {
    await kv.put("rating:meta", JSON.stringify({ sum: 0, count: 0 }));
    return json({ ok: true, reset: true }, 200, context.request);
  }

  return json({ ok: false, error: "未知操作" }, 400, context.request);
}

export async function onRequest(context) {
  if (context.request.method === "OPTIONS") return new Response(null, { status: 204, headers: { ...corsHeaders(context.request) } });
  if (context.request.method === "POST") return onPost(context);
  return json({ ok: false, error: "Method Not Allowed" }, 405, context.request);
}
