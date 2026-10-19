// MLSBD (mlsbd.co) — 1:1 TS port of MlsbdProvider.kt (CNCVerse-verified selectors)
// getMainPage / search / load / loadLinks

import {
  fetchHtmlCached, stripTags, decodeEntities, anchors, imgs, abs,
} from "../lib/util.js";
import { resolveHost, resolveSavelinks, qualityInt } from "./hosts.js";

export const config = {
  id: "mlsbd",
  name: "MLSBD",
  mainUrl: "https://mlsbd.co",
  lang: "bn",
  icon: "🎬",
  categories: [
    ["", "Latest Movies"],
    ["category/bangla-dubbed", "Bangla Dubbed"],
    ["category/dual-audio-movies", "Multi Audio"],
    ["category/tv-series", "TV Series"],
    ["category/bollywood-movies", "Bollywood"],
    ["category/bangla-movies", "Bengali"],
    ["category/hollywood-movies", "Hollywood"],
  ],
};

function isSeries(url, title) {
  return url.includes("/tv-series/") ||
    /\bs\d{1,2}(?:e\d{1,3})?\b|season\s*\d|web.?series|\bepisode\b/i.test(title) ||
    /\bs\d{1,2}(?:e\d{1,3})?\b|season\s*\d|web.?series|\bepisode\b/i.test(url);
}

// ------------------------------------------------------------------ cards
export function parseCards(html, baseUrl) {
  const out = [];
  const seen = new Set();
  // chunk by card marker
  const parts = html.split(/<div[^>]+class=["'][^"']*single-post[^"']*["'][^>]*>/i);
  for (let i = 1; i < parts.length; i++) {
    const chunk = parts[i].slice(0, 6000);
    let title = "";
    const tm = chunk.match(/class=["'][^"']*post-title[^"']*["'][^>]*>([\s\S]*?)<\/(?:h2|h3|a|div|p)>/i);
    if (tm) title = stripTags(tm[1]);
    if (!title) {
      const h2 = chunk.match(/<h2[^>]*>([\s\S]*?)<\/h2>/i);
      if (h2) title = stripTags(h2[1]);
    }
    if (!title) continue;
    // first thumb anchor
    let href = "";
    const thumbIdx = chunk.search(/class=["'][^"']*thumb[^"']*["']/i);
    const scope = thumbIdx >= 0 ? chunk.slice(thumbIdx, thumbIdx + 2500) : chunk;
    const am = scope.match(/<a\b[^>]*href=["']([^"']+)["']/i);
    if (am) href = abs(baseUrl, am[1]);
    if (!href.startsWith("http")) continue;
    if (seen.has(href)) continue;
    seen.add(href);
    let poster = null;
    const im = scope.match(/<img\b[^>]*>/i);
    if (im) {
      const tag = im[0];
      const src = (tag.match(/(?:data-src|src)\s*=\s*["']([^"']+)["']/i) || [])[1] || "";
      poster = src ? abs(baseUrl, decodeEntities(src)) : null;
      if (!poster) {
        const ss = (tag.match(/srcset\s*=\s*["']([^"'\s]+)/i) || [])[1];
        poster = ss ? abs(baseUrl, decodeEntities(ss)) : null;
      }
    }
    out.push({
      name: title,
      url: href,
      poster,
      type: isSeries(href, title) ? "TvSeries" : "Movie",
    });
  }
  return out;
}

// ------------------------------------------------------------- main page
export async function getMainPage(page = 1, cat = "") {
  const path = String(cat || "").replace(/^\/+|\/+$/g, "");
  let url;
  if (page <= 1) url = path ? `${config.mainUrl}/${path}/` : `${config.mainUrl}/`;
  else url = path ? `${config.mainUrl}/${path}/page/${page}/` : `${config.mainUrl}/page/${page}/`;
  const r = await fetchHtmlCached(url, { timeoutMs: 20000 });
  if (!r.ok && !r.text) return { title: categoryName(path), items: [], hasNext: false };
  const items = parseCards(r.text, r.url);
  return { title: categoryName(path), items, hasNext: items.length > 0 };
}

function categoryName(path) {
  const c = config.categories.find(([k]) => k === path);
  return c ? c[1] : "Latest Movies";
}

export async function search(query) {
  const q = (query || "").trim();
  if (!q) return [];
  const r = await fetchHtmlCached(`${config.mainUrl}/?s=${encodeURIComponent(q)}`, { timeoutMs: 20000 });
  if (!r.ok && !r.text) return [];
  return parseCards(r.text, r.url);
}

// ------------------------------------------------------------------ load
function qualityLabelOf(text, classes) {
  const t = (text || "").toLowerCase();
  if (t.includes("2160") || t.includes("4k")) return "4K";
  if (t.includes("1080")) return "1080p";
  if (t.includes("720")) return "720p";
  if (t.includes("480")) return "480p";
  if (t.includes("360")) return "360p";
  if (classes.includes("hevc")) return "HEVC";
  if (classes.includes("hd")) return "HD";
  if (classes.includes("sd")) return "SD";
  return "Unknown";
}

export async function load(url) {
  const r = await fetchHtmlCached(url, { timeoutMs: 20000 });
  if (!r.ok && !r.text) throw new Error("post unreachable: " + url);
  return parseLoad(r.text, url);
}

export function parseLoad(html, url) {

  // title
  let title = "";
  const nm = html.match(/class=["'][^"']*\bname\b[^"']*["'][^>]*>([\s\S]*?)</i);
  if (nm) title = stripTags(nm[1]);
  if (!title) title = stripTags((html.match(/<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']/i) || [])[1] || "");
  if (!title) title = stripTags((html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i) || [])[1] || "");
  if (!title) title = url;
  const yearM = title.match(/\((\d{4})\)/);
  const year = yearM ? +yearM[1] : null;

  // poster: img.aligncenter else og:image
  let poster = "";
  const aligncenter = html.match(/<img\b[^>]*class=["'][^"']*aligncenter[^"']*["'][^>]*>/i)
    || html.match(/<img\b[^>]*class=["'][^"']*aligncenter[^"']*["']/i);
  if (aligncenter) {
    poster = (aligncenter[0].match(/src\s*=\s*["']([^"']+)["']/i) || [])[1] || "";
  }
  if (!poster) poster = (html.match(/<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']/i) || [])[1] || "";
  poster = poster ? abs(url, decodeEntities(poster)) : null;

  // plot blocks
  const plotParts = [];
  for (const cls of ["single-post-title", "misc", "details", "storyline", "production", "media"]) {
    const re = new RegExp(`class=["'][^"']*${cls}[^"']*["'][^>]*>([\\s\\S]*?)<\\/div>`, "i");
    const m = html.match(re);
    if (m) {
      const t = stripTags(m[1]);
      if (t) plotParts.push(t);
    }
  }
  let plot = [...new Set(plotParts)].join("\n");
  if (!plot) plot = stripTags((html.match(/<meta[^>]+name=["']description["'][^>]+content=["']([^"']+)["']/i) || [])[1] || "");

  // tags
  const tags = [...new Set(
    (html.match(/<a\b[^>]+rel=["']tag["'][^>]*>([\s\S]*?)<\/a>/gi) || [])
      .map((a) => stripTags(a.replace(/^<a\b[^>]*>/i, "").replace(/<\/a>$/i, "")))
      .filter(Boolean)
  )].slice(0, 6);

  // download sections: div.post-section-title + following anchors (sd/hd/hevc)
  const sections = new Map(); // title -> [{quality,url}]
  const secRe = /<div[^>]+class=["'][^"']*post-section-title[^"']*["'][^>]*>([\s\S]*?)<\/div>([\s\S]*?)(?=<div[^>]+class=["'][^"']*post-section-title|<footer|$)/gi;
  let sm;
  while ((sm = secRe.exec(html))) {
    const secTitle = stripTags(sm[1]) || "Download Now";
    if (!sections.has(secTitle)) sections.set(secTitle, []);
    const bucket = sections.get(secTitle);
    for (const a of anchors(sm[2])) {
      if (!a.classes.some((c) => c === "sd" || c === "hd" || c === "hevc")) continue;
      const href = abs(url, a.href).trim();
      if (!href.startsWith("http")) continue;
      if (!bucket.some((x) => x.url === href)) bucket.push({ quality: qualityLabelOf(a.text, a.classes), url: href });
    }
  }
  // resilience: page-wide sd/hd/hevc anchors
  if (![...sections.values()].some((b) => b.length)) {
    const bucket = [];
    sections.set("Download Now", bucket);
    for (const a of anchors(html)) {
      if (!a.classes.some((c) => c === "sd" || c === "hd" || c === "hevc")) continue;
      const href = abs(url, a.href).trim();
      if (href.startsWith("http") && !bucket.some((x) => x.url === href)) {
        bucket.push({ quality: qualityLabelOf(a.text, a.classes), url: href });
      }
    }
  }

  const epRegex = /Epi-?\s*(\d{1,3})(?:-(\d{1,3}))?/gi;
  const sectionList = [...sections.entries()];
  let epSectionCount = 0;
  for (const [t] of sectionList) {
    epRegex.lastIndex = 0;
    if (epRegex.test(t)) epSectionCount++;
  }
  const isSeriesPost = isSeries(url, title) || epSectionCount >= 2;

  if (isSeriesPost) {
    const episodes = [];
    for (const [secTitle, links] of sectionList) {
      if (!links.length) continue;
      epRegex.lastIndex = 0;
      const em = epRegex.exec(secTitle);
      const start = em ? +em[1] : 0;
      const end = em && em[2] ? +em[2] : start;
      const a1 = start <= 0 ? 1 : start;
      const a2 = end <= 0 ? a1 : end;
      const qualities = [...new Set(links.map((x) => (x.quality.match(/(\d{3,4})p/) || [])[0]).filter(Boolean))].join("/");
      for (let ep = a1; ep <= a2; ep++) {
        episodes.push({
          name: `Episode ${ep}${qualities ? " · " + qualities : ""}`,
          season: 1,
          episode: ep,
          data: links.map((x) => `${x.quality}|${x.url}`).join(" ; "),
        });
      }
    }
    episodes.sort((a, b) => (a.episode || 0) - (b.episode || 0));
    return {
      title, url, poster: poster || null, plot: plot || null,
      tags: tags.length ? tags : null, year, type: "TvSeries", episodes,
    };
  }
  const all = [];
  const seenU = new Set();
  for (const links of sections.values()) {
    for (const x of links) {
      if (!seenU.has(x.url)) { seenU.add(x.url); all.push(x); }
    }
  }
  return {
    title, url, poster: poster || null, plot: plot || null,
    tags: tags.length ? tags : null, year, type: "Movie",
    data: all.map((x) => `${x.quality}|${x.url}`).join(" ; "),
  };
}

// ------------------------------------------------------------- loadLinks
const BAD_LINK = ["savelinks.me", "mlsbd.co", "t.me", "telegram", "whatsapp", "facebook", "twitter", "javascript:", "login"];
function isHostLink(link) {
  const l = link.toLowerCase();
  if (!l.startsWith("http")) return false;
  return !BAD_LINK.some((b) => l.includes(b));
}

export async function loadLinks(data) {
  if (!data) return [];
  const counted = new Set();
  const found = [];
  for (const item of data.split(" ; ")) {
    const parts = item.split("|");
    const link = (parts.length > 1 ? parts[1] : item).trim();
    if (!link.startsWith("http")) continue;
    try {
      if (link.includes("savelinks")) {
        const hosts = await resolveSavelinks(link, config.mainUrl);
        for (const host of hosts) {
          if (!isHostLink(host)) continue;
          const resolved = await resolveHost(host, `${config.mainUrl}/`);
          for (const rl of resolved) {
            if (!counted.has(rl.url)) { counted.add(rl.url); found.push(rl); }
          }
        }
      } else {
        const resolved = await resolveHost(link, `${config.mainUrl}/`);
        for (const rl of resolved) {
          if (!counted.has(rl.url)) { counted.add(rl.url); found.push(rl); }
        }
      }
    } catch {}
  }
  return found;
}
