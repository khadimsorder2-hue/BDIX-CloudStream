// SouthFreak (southfreak.vip) — 1:1 TS port of SouthFreakProvider.kt
// WP REST catalog + post load + techzed -> hosts link resolution

import {
  fetchHtmlCached, fetchJsonCached, decodeEntities, stripTags,
} from "../lib/util.js";
import { resolveTechzed, qualityInt, qualityLabel as qLabel, sizeOf } from "./hosts.js";

export const config = {
  id: "southfreak",
  name: "SouthFreak",
  mainUrl: "https://southfreak.vip",
  lang: "hi",
  icon: "🌶️",
  categories: [
    ["latest", "Latest"],
    ["820", "Hindi Dubbed"],
    ["819", "Dual Audio"],
    ["653", "Bengali"],
    ["4", "Bollywood"],
    ["3701", "Hollywood"],
    ["5", "Hindi"],
    ["303", "Telugu"],
    ["208", "Tamil"],
    ["69", "Malayalam"],
    ["6281", "Kannada"],
    ["4006", "Korean Movie & Drama"],
    ["30400", "Punjabi"],
    ["32467", "Japanese"],
    ["37670", "China + Malaysia"],
    ["33410", "Thai + Indonesia"],
    ["36397", "Spanish"],
    ["28805", "Turkish"],
    ["6164", "Persian"],
    ["37933", "Western"],
    ["37531", "Bangla Dubbed"],
    ["35", "Bangla Subtitle"],
    ["1", "Anime (JPN)"],
    ["7077", "WEB/TV Series"],
    ["29937", "IMDb 250"],
    ["33830", "Documentary"],
    ["37146", "Natok & Telefilm"],
    ["37975", "Reality Show"],
    ["37976", "Show"],
    ["38088", "Wrestling"],
    ["12223", "Upcoming"],
    ["3", "Action"],
    ["10", "Drama"],
    ["8", "Thriller"],
    ["9", "Comedy"],
    ["6", "Romantic"],
    ["7", "Crime"],
    ["33", "Adventure"],
    ["29372", "Horror"],
    ["13", "Mystery"],
    ["1887", "Sci-Fi"],
    ["33193", "Fantasy"],
    ["33192", "Family"],
    ["6983", "Animation"],
    ["11", "Biography"],
    ["32798", "History"],
    ["12", "Sports"],
    ["36891", "Musical"],
    ["36807", "War"],
    ["37120", "Melodrama"],
  ],
};

function seriesHint(text) {
  const t = (text || "").toLowerCase();
  return [" s0", "s01", "s02", "s03", "season", "web series", "webseries",
    "complete", " episode", "e03", "e02", "e01", "natok", "telefilm"]
    .some((k) => t.includes(k));
}

function posterOf(contentHtml) {
  if (!contentHtml) return null;
  const m = contentHtml.match(/<img[^>]+src=["'](https?:\/\/[^"']+)["']/i);
  return m ? m[1] : null;
}

function toSearchResponse(title, url, poster) {
  return { name: title, url, poster, type: seriesHint(title) ? "TvSeries" : "Movie" };
}

// ---------------- catalog + search over the open REST API ----------------
async function wpPosts(apiUrl) {
  const posts = await fetchJsonCached(apiUrl, 600);
  if (!Array.isArray(posts)) return [];
  return posts
    .filter((p) => p && p.link && p.title)
    .map((post) => {
      const title = decodeEntities(post.title.rendered || "").trim();
      return toSearchResponse(title, post.link, posterOf(post.content && post.content.rendered));
    })
    .filter((x) => x.name);
}

export async function getMainPage(page = 1, cat = "latest") {
  const url = cat === "latest"
    ? `${config.mainUrl}/wp-json/wp/v2/posts?per_page=20&page=${page}`
    : `${config.mainUrl}/wp-json/wp/v2/posts?categories=${cat}&per_page=20&page=${page}`;
  const items = await wpPosts(url);
  const title = (config.categories.find(([k]) => k === cat) || [null, "Latest"])[1];
  return { title, items, hasNext: items.length >= 20 };
}

export async function search(query, page = 1) {
  const q = (query || "").trim();
  if (!q) return [];
  const url = `${config.mainUrl}/wp-json/wp/v2/posts?search=${encodeURIComponent(q)}&per_page=20&page=${page}`;
  const items = await wpPosts(url);
  const seen = new Set();
  return items.filter((i) => (seen.has(i.url) ? false : (seen.add(i.url), true)));
}

// ---------------- load: post page ----------------
export async function load(url) {
  const r = await fetchHtmlCached(url, { timeoutMs: 20000 });
  if (!r.ok && !r.text) throw new Error("post unreachable: " + url);
  return parsePost(r.text, url);
}

export function parsePost(html, url) {

  // <title> = "<Post Title> - SouthFreak | ..."
  let pageTitle = (html.match(/<title[^>]*>([\s\S]*?)<\/title>/i) || [])[1] || "";
  pageTitle = pageTitle.split(" - SouthFreak")[0].trim();
  const h1 = stripTags((html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i) || [])[1] || "");
  const title = pageTitle || h1 || "Unknown";

  // poster: p.poster img else first http img
  let poster = null;
  const pm = html.match(/<p[^>]+class=["'][^"']*poster[^"']*["'][^>]*>\s*<img[^>]+src=["']([^"']+)["']/i)
    || html.match(/<img[^>]+src=["'](https?:\/\/[^"']+)["']/i);
  if (pm) poster = decodeEntities(pm[1]);

  // red metadata block: <p> containing IMDb + ":" — lines separated by <br>
  const metaLines = [];
  const pRe = /<p([^>]*)>([\s\S]*?)<\/p>/gi;
  let pm2;
  while ((pm2 = pRe.exec(html))) {
    const ptext = stripTags(pm2[2]);
    if (/imdb/i.test(ptext) && ptext.includes(":")) {
      for (const line of pm2[2].split(/<br\s*\/?>/i)) {
        const t = stripTags(line);
        if (t) metaLines.push(t);
      }
      break;
    }
  }
  const metaValue = (key) => {
    const line = metaLines.find((l) => l.toLowerCase().startsWith(key.toLowerCase()));
    if (!line) return null;
    const idx = line.indexOf(":");
    return idx >= 0 ? line.slice(idx + 1).trim() : null;
  };
  const plot = metaValue("Storyline");
  const language = metaValue("Language");
  const genres = (metaValue("Genres") || "").split(",").map((g) => g.trim()).filter(Boolean);
  const imdbRating = metaValue("IMDb Rating");
  const qualityMeta = metaValue("Quality");
  const sizeMeta = metaValue("Size");

  // download buttons -> techzed codes with labels
  const files = [];
  const seen = new Set();
  const aRe = /<a\b([^>]*)>([\s\S]*?)<\/a>/gi;
  let am;
  while ((am = aRe.exec(html))) {
    const href = (am[1].match(/href=["']([^"']+)["']/i) || [])[1] || "";
    if (!href.includes("techzed.info/view/")) continue;
    const abs = href.replace(/&amp;/g, "&");
    if (seen.has(abs)) continue;
    seen.add(abs);
    const label = decodeEntities(stripTags(am[2])) || "Download";
    files.push({ url: abs, label });
  }
  if (!files.length) {
    for (const u of [...new Set(html.match(/https:\/\/techzed\.info\/view\/[A-Za-z0-9]+/g) || [])]) {
      files.push({ url: u, label: "Download" });
    }
  }
  if (!files.length) files.push({ url, label: "Open post page" });

  const isSeries = seriesHint(title);

  const plotExtras = [];
  if (imdbRating && imdbRating !== "x") {
    const clean = imdbRating.replace(/\/10\s*$/, "");
    plotExtras.push(`IMDb: ${clean}/10`);
  }
  if (language) plotExtras.push(`Language: ${language}`);
  if (qualityMeta) plotExtras.push(`Quality: ${qualityMeta}`);
  if (sizeMeta && sizeMeta.toLowerCase() !== "see below") plotExtras.push(`Size: ${sizeMeta}`);
  if (plot) plotExtras.push(plot);
  const fullPlot = plotExtras.join("\n") || null;
  const tagsOut = genres.length ? genres
    : [language, isSeries ? "Web Series" : null].filter((x) => x && x.trim());

  if (isSeries) {
    const episodes = files.map((f) => {
      const q = qLabel(f.label) || "Full";
      const size = sizeOf(f.label);
      return {
        name: ["Complete", q, size].filter(Boolean).join(" · "),
        season: 1,
        episode: 1,
        data: JSON.stringify({ url: f.url, label: f.label }),
      };
    });
    return {
      title, url, poster, plot: fullPlot, tags: tagsOut.length ? tagsOut : null,
      year: null, type: "TvSeries", episodes,
    };
  }
  return {
    title, url, poster, plot: fullPlot, tags: tagsOut.length ? tagsOut : null,
    year: null, type: "Movie",
    data: files.map((f) => JSON.stringify({ url: f.url, label: f.label })).join("|"),
  };
}

// ---------------- links: techzed -> hosts ----------------
export async function loadLinks(data) {
  if (!data) return [];
  const entries = data.split("|")
    .map((x) => { try { return JSON.parse(x); } catch { return null; } })
    .filter((e) => e && e.url);
  if (!entries.length) return [];
  const found = [];
  for (const entry of entries) {
    const pageUrl = entry.url;
    const label = entry.label || "Download";
    const size = sizeOf(label);
    const hosts = await resolveTechzed(pageUrl, config.mainUrl);
    if (!hosts.length) {
      found.push({ label: `${config.name} ${size}`.trim(), url: pageUrl, quality: 0, referer: `${config.mainUrl}/`, direct: false });
      continue;
    }
    for (const h of hosts) {
      found.push({
        label: h.label,
        url: h.url,
        quality: h.quality || qualityInt(label),
        referer: h.referer,
        direct: h.direct,
      });
    }
  }
  return found;
}
