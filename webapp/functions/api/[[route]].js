// Pages Functions API router: /api/*
// GET  /api/sources
// GET  /api/:source/home?cat=&page=
// GET  /api/:source/search?q=&page=
// GET  /api/:source/detail?url=
// GET  /api/:source/links?data=   (url-encoded packed data)
// GET  /api/proxy?u=&ref=         (UA/referer-rewriting stream proxy)

import { json, err } from "../lib/util.js";
import * as mlsbd from "../sources/mlsbd.js";
import * as southfreak from "../sources/southfreak.js";
import { DESKTOP_UA } from "../lib/util.js";

const SOURCES = { mlsbd, southfreak };

export async function onRequest(context) {
  const { request } = context;
  const url = new URL(request.url);
  const range = request.headers.get("range") || "";
  const path = url.pathname.replace(/^\/api\/?/, "").replace(/\/+$/, "");
  const parts = path.split("/").filter(Boolean);
  const [head, ...rest] = parts;

  if (request.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        "access-control-allow-origin": "*",
        "access-control-allow-methods": "GET,POST,OPTIONS",
        "access-control-allow-headers": "content-type,range",
        "access-control-expose-headers": "content-range,content-length,accept-ranges",
      },
    });
  }

  try {
    // sources list
    if (head === "sources" || head === "") {
      return json({
        sources: Object.values(SOURCES).map((s) => ({
          id: s.config.id, name: s.config.name, lang: s.config.lang,
          icon: s.config.icon, categories: s.config.categories,
        })),
      });
    }

    // proxy (must be before source dispatch)
    if (head === "proxy") return handleProxy(url, range);

    // debug: what does the worker's fetch see?
    if (head === "debug") {
      const target = url.searchParams.get("u");
      if (!target) return err("missing u", 400);
      const r = await fetch(target, { headers: { "user-agent": DESKTOP_UA, accept: "application/json,text/html" }, redirect: "follow" });
      const text = await r.text();
      return json({ status: r.status, contentType: r.headers.get("content-type"), finalUrl: r.url, head: text.slice(0, 400) });
    }

    const src = SOURCES[head];
    if (!src) return err("unknown source: " + head, 404);
    const action = rest[0];

    if (action === "home") {
      const cat = url.searchParams.get("cat") || "";
      const page = parseInt(url.searchParams.get("page") || "1") || 1;
      return json(await src.getMainPage(page, cat));
    }
    if (action === "search") {
      const q = url.searchParams.get("q") || "";
      const page = parseInt(url.searchParams.get("page") || "1") || 1;
      return json({ items: await src.search(q, page) });
    }
    if (action === "detail") {
      const target = url.searchParams.get("url");
      if (!target) return err("missing url", 400);
      return json(await src.load(target));
    }
    if (action === "links") {
      const data = url.searchParams.get("data");
      if (!data) return err("missing data", 400);
      return json({ links: await src.loadLinks(data) });
    }
    return err("unknown action: " + action, 404);
  } catch (e) {
    return err(e.message || String(e), 502);
  }
}

/**
 * Stream proxy: rewrites UA + optional referer, passes Range through.
 * Only used for hosts that block browser UA or need referer (quota-safe:
 * direct r2.dev links bypass this entirely).
 */
async function handleProxy(url, range) {
  const target = url.searchParams.get("u");
  const ref = url.searchParams.get("ref") || "";
  if (!target) return err("missing u", 400);
  let t;
  try { t = new URL(target); } catch { return err("bad u", 400); }
  if (!/^https?:$/.test(t.protocol)) return err("bad protocol", 400);

  const headers = { "user-agent": DESKTOP_UA, accept: "*/*" };
  if (ref) headers.referer = ref;
  if (range) headers.range = range;

  const upstream = await fetch(t.toString(), { headers, redirect: "follow" });
  const h = new Headers();
  for (const k of ["content-type", "content-length", "content-range", "accept-ranges", "etag", "last-modified"]) {
    const v = upstream.headers.get(k);
    if (v) h.set(k, v);
  }
  h.set("access-control-allow-origin", "*");
  h.set("cache-control", "public, max-age=3600");
  return new Response(upstream.body, { status: upstream.status, headers: h });
}
