// GET  /api/comments            → 列 comments/ 目录（前端通常直读 api.github.com/raw）
// POST /api/comments {nick,text} 写 comments/<id>.json 到公开仓库；违禁词/URL/媒体/频率沿用 _lib。
import {
  json, corsHeaders, hasForbidden, extractClientIp, ipHash, readBody,
  ghList, ghGetJSON, ghPut,
} from "./_lib.js";

const NICK_MAX = 20, TEXT_MAX = 300;

export async function onGet(context) {
  try {
    const files = await ghList(context.env, "contents/comments");
    const items = await Promise.all(files.map((f) => ghGetJSON(context.env, "contents/comments/" + f.name).then((c) => c).catch(() => null)));
    const list = items.filter(Boolean).sort((a, b) => (b.ts || 0) - (a.ts || 0));
    return json({ ok: true, comments: list }, 200, context.request);
  } catch (e) {
    return json({ ok: false, error: "读取失败" }, 500, context.request);
  }
}

export async function onPost(context) {
  const env = context.env;
  if (!env.GITHUB_TOKEN) return json({ ok: false, error: "写评论服务未配置 GITHUB_TOKEN" }, 500, context.request);
  const body = await readBody(context.request);
  let nick = String(body.nick || "").trim().slice(0, NICK_MAX);
  const text = String(body.text || "").trim().slice(0, TEXT_MAX);
  if (!nick) nick = "匿名网友" + Math.floor(1000 + Math.random() * 9000);
  if (!text) return json({ ok: false, error: "评论内容为空" }, 400, context.request);
  if (text.length > TEXT_MAX) return json({ ok: false, error: "评论过长（≤300字）" }, 400, context.request);
  const bad = hasForbidden(nick + " " + text);
  if (bad) return json({ ok: false, error: bad }, 400, context.request);

  // 频率：同 IP 15s
  const ip = extractClientIp(context.request);
  const rlPath = "contents/limits/cmt-" + ipHash(ip) + ".json";
  let last = 0;
  try { last = Number((await ghGetJSON(env, rlPath)) || 0); } catch (e) {}
  const now = Date.now();
  if (now - last < 15000) return json({ ok: false, error: "发言太快，请稍后再试" }, 429, context.request);

  const id = now.toString(36) + "-" + Math.random().toString(36).slice(2, 8);
  const comment = { id, nick, text, ts: now };
  await ghPut(env, "contents/comments/" + id + ".json", comment, "comment");
  await ghPut(env, rlPath, now, "cmt-rate");
  return json({ ok: true, comment }, 200, context.request);
}

export async function onRequest(context) {
  if (context.request.method === "OPTIONS") return new Response(null, { status: 204, headers: { ...corsHeaders(context.request) } });
  if (context.request.method === "GET") return onGet(context);
  if (context.request.method === "POST") return onPost(context);
  return json({ ok: false, error: "Method Not Allowed" }, 405, context.request);
}
