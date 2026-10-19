// Shared link-host resolvers (ported from Extractors.kt patterns verified in Task 12/13)
// Every resolver returns [{label, url, quality, referer, direct}]

import { fetchHtmlCached, anchors } from "../lib/util.js";

export function qualityLabel(text = "") {
  const m = String(text).match(/\b(2160|1440|1080|720|576|480|360|240)p?\b|\b4k\b/i);
  if (!m) return null;
  const t = m[0].toLowerCase();
  if (t === "4k") return "2160p";
  return t.endsWith("p") ? t : t + "p";
}
export function qualityInt(text = "") {
  const q = qualityLabel(text);
  return q ? parseInt(q) : 0;
}
export function sizeOf(text = "") {
  const m = String(text).match(/[\d.]+\s*[GM]B/i);
  return m ? m[0] : "";
}

/**
 * GDFlix clone page (gdflix.* / gdxfiles.*): embeds direct
 * pub-*.r2.dev?token= object links + instant.busycdn.xyz links.
 * TTL=60s: r2 tokens are short-lived, long cache = dead links.
 * Port of resolveGdflix().
 */
export async function resolveGdflix(url) {
  const r = await fetchHtmlCached(url, { timeoutMs: 15000 }, 60);
  if (!r.ok && !r.text) return [];
  const html = r.text;
  // <title> carries the real filename: "GDFlix | name.mkv"
  let filename = "download";
  const tm = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i);
  if (tm) filename = tm[1].replace(/^[^|]*\|\s*/, "").trim() || "download";
  const qL = qualityLabel(filename);
  const quality = qualityInt(filename);

  const links = [];
  const r2 = [...new Set((html.match(/https:\/\/pub-[A-Za-z0-9-]+\.r2\.dev\/[^"'<>\s\\]+/g) || [])
    .map((u) => u.replace(/&amp;/g, "&")))].slice(0, 2);
  for (const u of r2) links.push({ label: `${qL || "HD"} GDFlix R2`.trim(), url: u, quality, referer: url, direct: true });

  const busy = [...new Set(html.match(/https:\/\/instant\.busycdn\.xyz\/[A-Za-z0-9]+/g) || [])].slice(0, 1);
  for (const u of busy) links.push({ label: `${qL || "HD"} BusyCDN`.trim(), url: u, quality, referer: url, direct: true });

  if (!links.length) {
    const title = filename.replace(/\.[a-z0-9]+$/i, "").trim().slice(0, 60);
    links.push({ label: `GDFlix · ${title}`, url, quality, referer: url, direct: false });
  }
  return links;
}

/**
 * MultiCloud (multicloudlinks.*): dr*.multidownload.lol/d/ direct links on
 * the page + player.php ArtPlayer streamSrc watch URL. Port of resolveMulticloud().
 */
export async function resolveMulticloud(url) {
  const r = await fetchHtmlCached(url, { timeoutMs: 15000 }, 60);
  if (!r.ok && !r.text) return [];
  const html = r.text;
  const out = [];

  const direct = [...new Set((html.match(/https:\/\/dr\d*\.multidownload\.lol\/d\/[^"'<>\s\\]+/g) || [])
    .map((u) => u.replace(/&amp;/g, "&")))].slice(0, 1);
  for (const u of direct) out.push({ label: "MultiCloud Direct", url: u, quality: 0, referer: url, direct: true });

  const pm = html.match(/https:\/\/[^"'<>\s]*multicloudlinks\.com\/player\.php\/?\?v=[A-Za-z0-9]+/);
  if (pm) {
    const player = pm[0].replace("/player.php/", "/player.php");
    try {
      const pr = await fetchHtmlCached(player, { referer: url, timeoutMs: 15000 });
      const sm = pr.text.match(/(?:const|let|var)\s+streamSrc\s*=\s*["']([^"']+)["']/);
      const src = sm && sm[1]
        ? sm[1]
        : (pr.text.match(/["'](https?:\/\/[^"']*multidownload\.lol[^"']*)["']/) || [])[1];
      if (src) out.push({ label: "MultiCloud Watch", url: src.replace(/&amp;/g, "&"), quality: 0, referer: player, direct: true });
    } catch {}
  }
  return out;
}

/** hubcloud / hubdrive: JS-gated generator page — emit the page as fallback. */
export function hubcloudFallback(url) {
  const name = url.split("/").filter(Boolean).pop() || "HubCloud";
  return [{ label: `HubCloud · ${name}`.slice(0, 70), url, quality: 0, referer: url, direct: false }];
}

/** pixeldrain: direct api file link. */
export function pixeldrain(url) {
  const id = url.split("pixeldrain.com/")[1] || "";
  const clean = id.split("?")[0].split("/")[0].replace(/^\/+/, "").trim();
  if (!clean || clean.startsWith("api")) return [];
  return [{
    label: "Pixeldrain",
    url: `https://pixeldrain.com/api/file/${clean}?download`,
    quality: 0,
    referer: "https://pixeldrain.com/",
    direct: true,
  }];
}

/**
 * Generic page scraper: any direct video / r2.dev / busycdn / multidownload
 * URLs embedded in an unknown host page.
 */
export async function resolveGenericPage(url) {
  const r = await fetchHtmlCached(url, { timeoutMs: 15000 }, 60);
  if (!r.ok && !r.text) return [];
  const html = r.text;
  const out = [];
  const pats = [
    [/https:\/\/pub-[A-Za-z0-9-]+\.r2\.dev\/[^"'<>\s\\]+/g, "R2 Direct", true],
    [/https:\/\/instant\.busycdn\.xyz\/[A-Za-z0-9]+/g, "BusyCDN", true],
    [/https:\/\/dr\d*\.multidownload\.lol\/d\/[^"'<>\s\\]+/g, "MultiDownload", true],
    [/https?:\/\/[^"'<>\s]+\.(?:mp4|mkv|avi|webm)(?:\?[^"'<>\s]*)?/g, "Direct File", true],
  ];
  for (const [re, label, direct] of pats) {
    for (const u of [...new Set((html.match(re) || []).map((x) => x.replace(/&amp;/g, "&")))].slice(0, 3)) {
      out.push({ label, url: u, quality: qualityInt(u), referer: url, direct });
    }
    if (out.length) break;
  }
  return out;
}

/**
 * Resolve ONE host URL to playable links (host-dispatch table, port of
 * dispatchHost). `direct` true = streamable in <video> (possibly via proxy);
 * false = JS-gated page, web shows "open page" fallback.
 */
export async function resolveHost(link, referer) {
  const l = (link || "").toLowerCase();
  try {
    if (l.includes("pixeldrain.com")) return pixeldrain(link);
    if (l.includes("gdflix.") || l.includes("gdxfiles.")) return resolveGdflix(link);
    if (l.includes("multicloudlinks.") || l.includes("multidownload.")) return resolveMulticloud(link);
    if (l.includes("hubcloud.") || l.includes("hubdrive.")) return hubcloudFallback(link);
    if (/\.(mp4|mkv|avi|webm|mpd|m3u8)(\?|$)/i.test(link)) {
      return [{ label: "Direct", url: link, quality: qualityInt(link), referer: referer || "", direct: true }];
    }
    const generic = await resolveGenericPage(link);
    if (generic.length) return generic;
    return [{ label: "Page", url: link, quality: 0, referer: referer || "", direct: false }];
  } catch {
    return [];
  }
}

/** Savelinks view page: real file hosts sit in <ul><li><a href=http>. */
export async function resolveSavelinks(link, mainUrl) {
  const r = await fetchHtmlCached(link, { referer: `${mainUrl}/`, timeoutMs: 15000 }, 60);
  if (!r.ok && !r.text) return [];
  const pageUrl = r.url;
  const hosts = [];
  const listRe = /<ul[\s\S]*?<\/ul>/gi;
  let m;
  const listHtml = [];
  while ((m = listRe.exec(r.text))) listHtml.push(m[0]);
  const pool = listHtml.length ? listHtml.join("\n") : r.text;
  const bad = ["savelinks.me", "mlsbd.co", "t.me", "telegram", "whatsapp", "facebook", "twitter", "javascript:", "login"];
  for (const a of anchors(pool)) {
    const href = a.href.trim();
    if (!href.startsWith("http")) continue;
    const hl = href.toLowerCase();
    if (bad.some((b) => hl.includes(b))) continue;
    hosts.push(href);
  }
  return [...new Set(hosts)];
}

/** TechZed (SouthFreak): fetch view/<code> page, list mirrors, resolve each. */
export async function resolveTechzed(pageUrl, mainUrl) {
  const r = await fetchHtmlCached(pageUrl, {
    referer: `${mainUrl}/`,
    headers: { accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" },
    timeoutMs: 20000,
  }, 60);
  if (!r.ok && !r.text) return [];
  const mirrors = [...new Set((r.text.match(/href="(https?:\/\/[^"]+)"/g) || [])
    .map((x) => x.replace(/^href="/, "").replace(/"$/, "").replace(/&amp;/g, "&"))
    .filter((u) => !u.includes("techzed.info")))
    ].map((u) => u.split("?")[0] === u ? u : u); // keep query (r2 tokens live behind gdflix)
  const out = [];
  const seen = new Set();
  for (const mirror of mirrors) {
    const key = mirror.split("?")[0];
    if (seen.has(key)) continue;
    seen.add(key);
    const ml = mirror.toLowerCase();
    try {
      if (ml.includes("gdflix.") || ml.includes("gdxfiles.")) out.push(...(await resolveGdflix(mirror)));
      else if (ml.includes("multicloudlinks.")) out.push(...(await resolveMulticloud(mirror)));
      else if (ml.includes("hubcloud.")) out.push(...hubcloudFallback(mirror));
      // direct-dl.lol / gdxshare / filebee: login/CF-gated — skipped (same as Kotlin)
    } catch {}
  }
  return out.sort((a, b) => (b.url.includes("r2.dev") ? 1 : 0) - (a.url.includes("r2.dev") ? 1 : 0));
}
