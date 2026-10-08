// GET  /api/admin/ratings?score=&region=&device=   （需 X-Admin-Key）
// POST /api/admin/ratings  {action:"delete", id}  删票后【从目录现存票重算】 summary.json
// 数据存在公开仓库 ETQWFD/LocalDream-ET-ratings；鉴权沿用 _lib.checkAdmin。
import {
  json, corsHeaders, checkAdmin, readBody,
  ghList, ghGetJSON, ghDelete, ghPut,
} from "./_lib.js";

async function recompute(env) {
  const files = await ghList(env, "contents/ratings");
  const items = (await Promise.all(files.map((f) =>
    ghGetJSON(env, "contents/ratings/" + f.name)
      .then((c) => c && Object.assign({ id: f.name.replace(/\.json$/, "") }, c))
      .catch(() => null)))).filter(Boolean);
  const dist = { "1": 0, "2": 0, "3": 0, "4": 0, "5": 0 };
  let sum = 0, highest = 0;
  items.forEach((it) => { const s = Number(it.score); if (s >= 1 && s <= 5) { dist[String(s)]++; sum += s; highest = Math.max(highest, s); } });
  const summary = {
    avg: items.length ? Math.round((sum / items.length) * 10) / 10 : 0,
    count: items.length, highest, dist, updatedAt: new Date().toISOString(),
  };
  await ghPut(env, "contents/summary.json", summary, "recompute after delete");
  return { summary, items };
}

export async function onGet(context) {
  const env = context.env;
  if (!checkAdmin(context.request, env)) return json({ ok: false, error: "管理口令无效" }, 401, context.request);
  try {
    const u = new URL(context.request.url);
    const fScore = u.searchParams.get("score");
    const fRegion = (u.searchParams.get("region") || "").toLowerCase();
    const fDevice = (u.searchParams.get("device") || "").toLowerCase();
    const { summary, items } = await recompute(env);
    const filtered = items.filter((it) => {
      if (fScore && String(it.score) !== fScore) return false;
      if (fRegion && String(it.country + " " + (it.prov || "")).toLowerCase().indexOf(fRegion) < 0) return false;
      if (fDevice && String(it.model || "").toLowerCase().indexOf(fDevice) < 0) return false;
      return true;
    });
    return json({ ok: true, summary, items: filtered }, 200, context.request);
  } catch (e) {
    return json({ ok: false, error: "读取失败：" + e.message }, 500, context.request);
  }
}

export async function onPost(context) {
  const env = context.env;
  if (!checkAdmin(context.request, env)) return json({ ok: false, error: "管理口令无效" }, 401, context.request);
  if (!env.GITHUB_TOKEN) return json({ ok: false, error: "写操作未配置 GITHUB_TOKEN" }, 500, context.request);
  const body = await readBody(context.request);
  if (body.action !== "delete" || !body.id) return json({ ok: false, error: '需 {action:"delete", id}' }, 400, context.request);
  await ghDelete(env, "contents/ratings/" + body.id + ".json");
  const { summary } = await recompute(env);
  return json({ ok: true, summary }, 200, context.request);
}

export async function onRequest(context) {
  if (context.request.method === "OPTIONS") return new Response(null, { status: 204, headers: { ...corsHeaders(context.request) } });
  if (context.request.method === "GET") return onGet(context);
  if (context.request.method === "POST") return onPost(context);
  return json({ ok: false, error: "Method Not Allowed" }, 405, context.request);
}
