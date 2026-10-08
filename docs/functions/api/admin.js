// POST /api/admin  {action:"delete_comment"|"reset_rating", id?}
// 鉴权：请求头 X-Admin-Key 与环境变量 ADMIN_KEY 恒定时间比对；ADMIN_KEY 不进前端。
import { json, corsHeaders, checkAdmin, readBody, ghDelete, ghPut } from "./_lib.js";

export async function onPost(context) {
  const env = context.env;
  if (!checkAdmin(context.request, env)) return json({ ok: false, error: "管理口令无效或未配置 ADMIN_KEY" }, 401, context.request);
  if (!env.GITHUB_TOKEN) return json({ ok: false, error: "写操作未配置 GITHUB_TOKEN" }, 500, context.request);
  const body = await readBody(context.request);

  if (body.action === "delete_comment") {
    const id = String(body.id || "");
    if (!id) return json({ ok: false, error: "缺少评论 id" }, 400, context.request);
    const r = await ghDelete(env, "contents/comments/" + id + ".json");
    return json({ ok: r.ok, deleted: r.ok }, r.ok ? 200 : 500, context.request);
  }

  if (body.action === "reset_rating") {
    const empty = { avg: 0, count: 0, highest: 0, dist: { "1": 0, "2": 0, "3": 0, "4": 0, "5": 0 }, updatedAt: new Date().toISOString() };
    await ghPut(env, "contents/summary.json", empty, "reset rating");
    return json({ ok: true, reset: true }, 200, context.request);
  }

  return json({ ok: false, error: "未知操作" }, 400, context.request);
}

export async function onRequest(context) {
  if (context.request.method === "OPTIONS") return new Response(null, { status: 204, headers: { ...corsHeaders(context.request) } });
  if (context.request.method === "POST") return onPost(context);
  return json({ ok: false, error: "Method Not Allowed" }, 405, context.request);
}
