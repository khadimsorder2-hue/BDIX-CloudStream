// SouthFreak (southfreak.vip) — TS port of the original SouthFreakProvider.kt

import { getDoc, getRaw, loadHtml, DOC_HEADERS, decodeHtmlEntities, squash, qualityLabel, qualityInt } from "../fetcher";
import type { SearchItem, HomeRow, Detail, Episode, StreamLink } from "./types";
import { resolveGdflix, resolveMulticloud, resolveGofile } from "./extractors";

// USER-SET canonical domain: southfreak.vip (301 -> southfreak.club).
// This is the REAL SouthFreak (movies/series, no 18+ categories).
// southfreak.in is the adult clone — do NOT use it.
// Same WP REST layout + techzed.info download links as before.
const MAIN_URL = "https://southfreak.vip";
const TECHZED = "https://techzed.info";

export const SOUTHFREAK_CATEGORIES: [string, string][] = [
  ["latest", "Latest"],
  ["820", "Hindi Dubbed"],
  ["819", "Dual Audio"],
  ["653", "Bengali"],
  ["5", "Hindi"],
  ["3701", "Hollywood"],
  ["7077", "WEB/TV Series"],
  ["4006", "Korean Movie & Drama"],
  ["3", "Action"],
  ["6", "Romantic"],
  ["10", "Drama"],
  ["9", "Comedy"],
  ["7", "Crime"],
  ["8", "Thriller"],
  ["29372", "Horror"],
  ["1887", "Sci-Fi"],
  ["6983", "Animation"],
  ["33830", "Documentary"],
];

const SF_HEADERS = {
  "User-Agent": DOC_HEADERS["User-Agent"],
  "Accept-Language": "en-US;q=0.9,en;q=0.8",
};

interface WpPost { id?: number; link?: string; title?: { rendered?: string }; content?: { rendered?: string } }

function seriesHint(text: string): boolean {
  const t = text.toLowerCase();
  return [" s0", "s01", "s02", "s03", "season", "web series", "webseries", "complete", " episode", "e03", "e02", "e01", "natok", "telefilm"]
    .some((x) => t.includes(x));
}

function posterOf(contentHtml: string | undefined | null): string | null {
  if (!contentHtml) return null;
  return /<img[^>]+src="(https?:\/\/[^"]+)"/i.exec(contentHtml)?.[1] ?? null;
}

async function wpPosts(apiUrl: string): Promise<SearchItem[]> {
  try {
    const { ok, text } = await getRaw(apiUrl, SF_HEADERS, 30000);
    if (!ok) return [];
    const posts = JSON.parse(text) as WpPost[];
    if (!Array.isArray(posts)) return [];
    const out: SearchItem[] = [];
    for (const post of posts) {
      const link = post.link;
      const title = post.title?.rendered ? decodeHtmlEntities(post.title.rendered.replace(/<[^>]+>/g, "")).trim() : null;
      if (!link || !title) continue;
      out.push({
        title,
        url: link,
        poster: posterOf(post.content?.rendered),
        type: seriesHint(title) ? "TvSeries" : "Movie",
      });
    }
    return out;
  } catch {
    return [];
  }
}

export async function southfreakHome(category: string, page: number): Promise<HomeRow> {
  // .vip/.club has no adult categories — plain latest listing is clean.
  const cat = category || "latest";
  const url = cat === "latest"
    ? `${MAIN_URL}/wp-json/wp/v2/posts?per_page=20&page=${page}`
    : `${MAIN_URL}/wp-json/wp/v2/posts?categories=${cat}&per_page=20&page=${page}`;
  const items = await wpPosts(url);
  return { title: "", items, hasNext: items.length >= 20 };
}

export async function southfreakSearch(q: string, page = 1): Promise<SearchItem[]> {
  const query = q.trim();
  if (!query) return [];
  const items = await wpPosts(
    `${MAIN_URL}/wp-json/wp/v2/posts?search=${encodeURIComponent(query)}&per_page=20&page=${page}`
  );
  const seen = new Set<string>();
  return items.filter((i) => (seen.has(i.url) ? false : (seen.add(i.url), true)));
}

interface SfFile { url: string; label: string }

function packSf(files: SfFile[]): string {
  return files.map((f) => JSON.stringify(f)).join("|");
}

function metaLines($: import("cheerio").CheerioAPI, bodySel: string): string[] {
  const p = $(`${bodySel} p`).toArray()
    .find((el) => /imdb/i.test($(el).text()) && ($(el).text().includes(":")));
  if (!p) return [];
  return $(p).html()?.split(/<br\s*\/?>/)
    .map((line) => squash(decodeHtmlEntities(line.replace(/<[^>]+>/g, " "))))
    .filter(Boolean) ?? [];
}

export async function southfreakLoad(url: string): Promise<Detail> {
  const { ok, $, text } = await getDoc(url, SF_HEADERS, 45000);
  if (!ok) throw new Error("post unreachable: " + url);

  const pageTitle = decodeHtmlEntities(text.match(/<title>([^<]*)<\/title>/i)?.[1]?.replace(/\s*[|\-–]\s*SouthFreak.*$/i, "").trim() ?? "");
  const title = pageTitle || squash(decodeHtmlEntities($("h1").text())) || "Unknown";

  const body = $("main.page-body").length ? "main.page-body" : "body";
  const poster = $(`${body} p.poster img`).attr("src") ||
    $(`${body} img[src^=http]`).first().attr("src") || null;

  const lines = metaLines($, body);
  const metaValue = (key: string): string | null =>
    lines.find((l) => l.toLowerCase().startsWith(key.toLowerCase()))?.split(":").slice(1).join(":").trim() || null;

  const plot = metaValue("Storyline");
  const language = metaValue("Language");
  const genres = metaValue("Genres")?.split(",").map((g) => g.trim()).filter(Boolean) ?? null;
  const imdbRating = metaValue("IMDb Rating")?.split("/")[0].trim() ?? null;
  const imdbClean = imdbRating && imdbRating !== "x" ? imdbRating : null;
  const qualityMeta = metaValue("Quality");
  const sizeMeta = metaValue("Size");

  // Download buttons -> techzed codes with quality labels.
  const files: SfFile[] = [];
  const seen = new Set<string>();
  for (const a of $(`${body} a[href*=techzed]`).toArray()) {
    const href = ($(a).attr("href") || "").replace(/&amp;/g, "&").trim();
    if (!href.includes("techzed.info/view/") || seen.has(href)) continue;
    seen.add(href);
    const label = squash(decodeHtmlEntities($(a).text())) || "Download";
    files.push({ url: href, label });
  }
  if (!files.length) {
    for (const m of new Set(text.match(/https:\/\/techzed\.info\/view\/[A-Za-z0-9]+/g) ?? [])) {
      files.push({ url: m, label: "Download" });
    }
  }
  if (!files.length) files.push({ url, label: "Open post page" });

  const isSeries = seriesHint(title);

  const plotExtras = [
    imdbClean ? `IMDb: ${imdbClean}/10` : null,
    language ? `Language: ${language}` : null,
    qualityMeta ? `Quality: ${qualityMeta}` : null,
    sizeMeta && sizeMeta.toLowerCase() !== "see below" ? `Size: ${sizeMeta}` : null,
    plot,
  ].filter(Boolean);
  const fullPlot = plotExtras.join("\n") || null;

  if (isSeries) {
    const episodes: Episode[] = files.map((f) => {
      const q = qualityLabel(f.label) ?? "Full";
      const size = /[\d.]+\s*[GM]B/i.exec(f.label)?.[0] ?? "";
      return {
        data: JSON.stringify(f),
        name: ["Complete", q, size].filter(Boolean).join(" · "),
        season: 1,
        episode: 1,
      };
    });
    return {
      source: "southfreak", title, url, poster, plot: fullPlot,
      tags: genres?.length ? genres : [language, "Web Series"].filter(Boolean),
      year: Number(/\b(19|20)\d{2}\b/.exec(title)?.[0] ?? 0) || null,
      type: "TvSeries", movieData: null, episodes,
      imdb: imdbClean, language, contentQuality: qualityMeta, size: sizeMeta,
    };
  }

  return {
    source: "southfreak", title, url, poster, plot: fullPlot,
    tags: genres?.length ? genres : [language].filter(Boolean),
    year: Number(/\b(19|20)\d{2}\b/.exec(title)?.[0] ?? 0) || null,
    type: "Movie", movieData: packSf(files), episodes: null,
    imdb: imdbClean, language, contentQuality: qualityMeta, size: sizeMeta,
  };
}

interface ResolvedHost { url: string; label: string; quality: number | null; referer: string }

/**
 * gdxshare.com/file/<code> — Laravel app: GET page (csrf token + cookies),
 * then POST the same path with action=original to mint the direct link.
 * Best effort: any failure falls back to the page itself.
 */
export async function resolveGdxShare(pageUrl: string, referer: string): Promise<StreamLink[]> {
  const UA = DOC_HEADERS["User-Agent"];
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 30000);
    let html: string, cookies: string;
    try {
      const res = await fetch(pageUrl, { headers: { "User-Agent": UA, Referer: referer }, signal: ctrl.signal });
      html = await res.text();
      const jar = res.headers.getSetCookie?.() ?? [];
      cookies = jar.map((c) => c.split(";")[0]).join("; ");
    } finally {
      clearTimeout(t);
    }
    const token = /csrf-token" content="([^"]+)/.exec(html)?.[1];
    if (!token) return [];

    const form = new FormData();
    form.append("action", "original");
    form.append("key", "");
    form.append("action_token", token);
    form.append("_token", token);
    const post = await fetch(pageUrl, {
      method: "POST",
      headers: {
        "User-Agent": UA,
        Referer: pageUrl,
        "x-token": "gdxshare.com",
        "X-Requested-With": "XMLHttpRequest",
        Accept: "application/json",
        Cookie: cookies,
      },
      body: form,
      signal: AbortSignal.timeout(30000),
    });
    const data = (await post.json().catch(() => null)) as { url?: string; error?: boolean } | null;
    if (data?.url && data.url.startsWith("http")) {
      return [{ name: "SouthFreak", label: "GDXShare Direct", url: data.url, quality: 0, referer: "https://gdxshare.com/" }];
    }
  } catch { /* fall through */ }
  return [{ name: "SouthFreak", label: "GDXShare Page", url: pageUrl, quality: 0, referer: "https://gdxshare.com/", isPage: true }];
}

async function resolveTechzed(pageUrl: string): Promise<ResolvedHost[]> {
  let html: string;
  let finalUrl: string;
  try {
    const res = await getRaw(pageUrl, { ...SF_HEADERS, Referer: `${MAIN_URL}/` }, 45000);
    if (!res.ok) return [];
    html = res.text;
    finalUrl = res.finalUrl;
  } catch {
    return [];
  }
  const mirrors = [...new Set(
    [...html.matchAll(/href="(https?:\/\/[^"]+)"/g)]
      .map((m) => m[1].replace(/&amp;/g, "&"))
      .filter((u) => !u.includes("techzed.info"))
      .map((u) => u.split("?")[0])
      .map((u) => u)
  )];
  // keep full urls (with query) for dispatch — dedupe by url prefix only for report
  const fullMirrors = [...html.matchAll(/href="(https?:\/\/[^"]+)"/g)]
    .map((m) => m[1].replace(/&amp;/g, "&"))
    .filter((u) => !u.includes("techzed.info"));

  const out: ResolvedHost[] = [];
  const emitted = new Set<string>();
  for (const mirror of fullMirrors) {
    if (emitted.has(mirror.split("?")[0])) continue;
    const m = mirror.toLowerCase();
    // ads / site chrome
    if (/jaya9|southfreak\.(wiki|vip|my|com)|t\.me|telegram|whatsapp|facebook|twitter|youtube|instagram/.test(m)) continue;
    let hosts: StreamLink[] = [];
    if (/gdflix\.|gdxfiles\./.test(mirror)) {
      hosts = await resolveGdflix(mirror, finalUrl);
    } else if (/gdxshare\./.test(mirror)) {
      hosts = await resolveGdxShare(mirror, finalUrl);
    } else if (/multicloudlinks\.|multidownload\.lol/.test(mirror)) {
      hosts = await resolveMulticloud(mirror, finalUrl);
    } else if (/gofile\.io/.test(mirror)) {
      hosts = await resolveGofile(mirror);
    } else if (/hubcloud\./.test(mirror)) {
      const name = mirror.split("/").filter(Boolean).pop() ?? "";
      hosts = [{ name: "HubCloud", label: `HubCloud · ${name}`.slice(0, 70), url: mirror, quality: 0, referer: mirror, isPage: true }];
    } else if (/send\.cm|1fichier|1cloudfile|filebee|filepress|pixeldrain/.test(m)) {
      const label = /send\.cm/.test(m) ? "SendCM" : /1fichier/.test(m) ? "1Fichier"
        : /1cloudfile/.test(m) ? "1CloudFile" : /pixeldrain/.test(m) ? "Pixeldrain" : "FileHost";
      hosts = [{ name: label, label: `${label} (download)`, url: mirror, quality: 0, referer: finalUrl, isPage: true }];
    }
    for (const h of hosts) {
      if (emitted.has(h.url.split("?")[0])) continue;
      emitted.add(h.url.split("?")[0]);
      out.push({ url: h.url, label: h.label, quality: h.quality, referer: h.referer });
    }
  }
  // Prefer direct R2 object storage, then multidownload.
  return out.sort((a, b) => Number(b.url.includes("r2.dev")) - Number(a.url.includes("r2.dev")));
}

export async function southfreakLoadLinks(data: string): Promise<StreamLink[]> {
  const out: StreamLink[] = [];
  const entries: SfFile[] = [];
  for (const chunk of data.split("|")) {
    try {
      const parsed = JSON.parse(chunk) as SfFile;
      if (parsed?.url) entries.push(parsed);
    } catch { /* skip */ }
  }
  if (!entries.length) return out;

  for (const entry of entries) {
    const label = entry.label || "Download";
    const quality = qualityInt(label);
    const size = /[\d.]+\s*[GM]B/i.exec(label)?.[0] ?? "";
    const hosts = await resolveTechzed(entry.url);
    if (!hosts.length) {
      out.push({ name: "SouthFreak", label: `SouthFreak ${size}`.trim(), url: entry.url, quality: 0, referer: `${MAIN_URL}/`, isPage: true });
      continue;
    }
    for (const h of hosts) {
      out.push({ name: "SouthFreak", label: h.label, url: h.url, quality: h.quality ?? quality, referer: h.referer });
    }
  }
  return out;
}
