// Shared helpers for BDIX-CloudStream web API (Pages Functions)
// Mirrors CloudStream's app.get() semantics: desktop UA, referer control.

export const DESKTOP_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

export const JSON_HDRS = { "content-type": "application/json; charset=utf-8" };

export function json(data, status = 200, extra = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      ...JSON_HDRS,
      "access-control-allow-origin": "*",
      "cache-control": "no-store",
      ...extra,
    },
  });
}

export function err(message, status = 502, extra = {}) {
  return json({ error: message }, status, extra);
}

/** fetch text with CloudStream-style headers; returns {status, ok, text, url} */
export async function fetchText(url, { headers = {}, referer = null, timeoutMs = 15000, method = "GET" } = {}) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const h = {
      "user-agent": DESKTOP_UA,
      "accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      "accept-language": "en-US;q=0.9,en;q=0.8",
      ...headers,
    };
    if (referer) h["referer"] = referer;
    const res = await fetch(url, { headers: h, redirect: "follow", signal: ctrl.signal, method });
    const text = await res.text();
    return { status: res.status, ok: res.ok, text, url: res.url || url };
  } finally {
    clearTimeout(t);
  }
}

/** fetch JSON with cache */
export async function fetchJsonCached(url, ttl = 600) {
  const cached = await cacheGet(url);
  if (cached) { try { return JSON.parse(cached); } catch {} }
  const res = await fetch(url, {
    headers: { "user-agent": DESKTOP_UA, accept: "application/json" },
  });
  const text = await res.text();
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    return [];
  }
  await cachePut(url, text, ttl);
  return data;
}

/** minimal Cache API wrappers (work on workers runtime) */
function cacheKey(url) {
  return new Request(url, { method: "GET" });
}
export async function cacheGet(url) {
  try {
    const hit = await caches.default.match(await cacheKey(url));
    if (hit) return await hit.text();
  } catch {}
  return null;
}
export async function cachePut(url, body, ttl = 600) {
  try {
    const res = new Response(body, {
      headers: { "cache-control": `public, max-age=${ttl}` },
    });
    await caches.default.put(await cacheKey(url), res.clone());
  } catch {}
}

/** cached HTML text fetch */
export async function fetchHtmlCached(url, opts = {}, ttl = 600) {
  const cached = await cacheGet("html:" + url);
  if (cached) return { status: 200, ok: true, text: cached, url };
  const r = await fetchText(url, opts);
  if (r.ok && r.text) await cachePut("html:" + url, r.text, ttl);
  return r;
}

// ---------------------------------------------------------------- HTML utils

export function decodeEntities(s = "") {
  return String(s)
    .replace(/&#0?38;?/g, "&").replace(/&amp;/g, "&")
    .replace(/&#0?8211;?/g, "-").replace(/&#0?8217;?/g, "'")
    .replace(/&#0?8220;?/g, '"').replace(/&#0?8221;?/g, '"')
    .replace(/&quot;/g, '"').replace(/&#0?39;?/g, "'")
    .replace(/&apos;/g, "'").replace(/&nbsp;/g, " ")
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(+d));
}

export function stripTags(s = "") {
  return decodeEntities(String(s).replace(/<[^>]*>/g, " ")).replace(/\s+/g, " ").trim();
}

/** get attribute value from a raw tag string */
export function attr(tag, name) {
  const re = new RegExp(`${name}\\s*=\\s*["']([^"']*)["']`, "i");
  const m = tag.match(re);
  return m ? decodeEntities(m[1]) : "";
}

/** all matches of <a ...>...</a> as {href, text, classes} */
export function anchors(html) {
  const out = [];
  const re = /<a\b([^>]*)>([\s\S]*?)<\/a>/gi;
  let m;
  while ((m = re.exec(html))) {
    const cls = (attr(m[1], "class") || "").split(/\s+/).filter(Boolean);
    out.push({ href: attr(m[1], "href"), text: stripTags(m[2]), classes: cls });
  }
  return out;
}

/** all <img> tags as {src, dataSrc, srcset} */
export function imgs(html) {
  const out = [];
  const re = /<img\b([^>]*)>/gi;
  let m;
  while ((m = re.exec(html))) {
    out.push({
      src: attr(m[1], "src"),
      dataSrc: attr(m[1], "data-src"),
      srcset: attr(m[1], "srcset"),
    });
  }
  return out;
}

/** absolute URL resolve */
export function abs(base, href) {
  if (!href) return "";
  try {
    return new URL(href, base).toString();
  } catch {
    return href;
  }
}

export function hostOf(url) {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return "";
  }
}
