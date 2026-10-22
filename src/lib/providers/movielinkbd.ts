// MovieLinkBD (movielinkbd.is) — TS port.
// Site is WordPress; posts embed a Plyr player whose source is an encoded
// direct CDN url:  plain = base64( reverse( "https://cdn.mlmbd.lol/<file>" ) ).
// Download buttons point at /generate/?file=<base64 filename>.

import { getDoc, getRaw, loadHtml, DOC_HEADERS, squash, qualityInt } from "../fetcher";
import type { Cheerp } from "../fetcher";
import type { SearchItem, HomeRow, Detail, Episode, StreamLink } from "./types";
import { isDirectMediaUrl } from "./extractors";

const MAIN_URL = "https://movielinkbd.is";
const UA = DOC_HEADERS;

export const MOVIELINKBD_CATEGORIES: [string, string][] = [
  ["", "Latest"],
  ["bangla", "Bangla Movies"],
  ["bangla-dubbed", "Bangla Dubbed"],
  ["hindi", "Hindi Movies"],
  ["hindi-dubbed", "Hindi Dubbed"],
  ["english-movies", "English Movies"],
  ["dual-audio", "Dual Audio"],
  ["web-series", "Web Series"],
];

function isSeries(url: string, title: string): boolean {
  return /web-series|\/series|season-\d|s\d{1,2}\s*e?\d*|episode/i.test(`${url} ${title}`);
}

function decodeEnc(enc: string): string | null {
  try {
    const rev = enc.replace(/\s+/g, "").split("").reverse().join("");
    const dec = atob(rev);
    return dec.startsWith("http") ? dec : null;
  } catch {
    return null;
  }
}

/** quality int from a filename/label */
function qInt(s: string): number {
  const m = /(\d{3,4})p/.exec(s);
  return m ? qualityInt(m[1]) : 0;
}

function parseCards($: Cheerp, base: string): SearchItem[] {
  const items: SearchItem[] = [];
  const seen = new Set<string>();
  // cards: <a href="post"> wrapping <img>; title from img alt or sibling text
  for (const a of $("a[href]").toArray()) {
    const href = ($(a).attr("href") || "").trim();
    if (!href.startsWith(base) && !href.startsWith("/")) continue;
    const abs = href.startsWith("http") ? href : `${base}${href}`;
    // detail posts = site root slug urls (not categories / pages)
    if (!/^https:\/\/movielinkbd\.is\/[a-z0-9][a-z0-9-]*\/$/.test(abs)) continue;
    if (/(\/wp-|\/category|\/tag|\/page\/|#|\/search|feed)/.test(abs)) continue;
    if (seen.has(abs)) continue;
    const img = $(a).find("img").first();
    if (!img.length) continue;
    const title =
      squash($(a).attr("title") || "") ||
      squash(img.attr("alt") || img.attr("title") || "") ||
      squash($(a).text());
    if (!title || title.length < 3) continue;
    seen.add(abs);
    const poster =
      (img.attr("data-src") || img.attr("src") || (img.attr("srcset") || "").split(" ")[0] || "").trim() || null;
    items.push({
      title,
      url: abs,
      poster: poster && poster.startsWith("http") ? poster : poster ? `${base}${poster}` : null,
      type: isSeries(abs, title) ? "TvSeries" : "Movie",
    });
  }
  return items;
}

export async function movielinkbdHome(category: string, page: number): Promise<HomeRow> {
  const path = category.replace(/^\/+|\/+$/g, "");
  const url = page <= 1
    ? path ? `${MAIN_URL}/${path}` : `${MAIN_URL}/`
    : path ? `${MAIN_URL}/${path}/page/${page}` : `${MAIN_URL}/page/${page}`;
  try {
    const { ok, $ } = await getDoc(url, UA, 30000);
    if (!ok) return { title: "", items: [], hasNext: false };
    const items = parseCards($, MAIN_URL);
    return { title: "", items, hasNext: items.length >= 10 };
  } catch {
    return { title: "", items: [], hasNext: false };
  }
}

export async function movielinkbdSearch(q: string): Promise<SearchItem[]> {
  try {
    const { ok, $ } = await getDoc(`${MAIN_URL}/?s=${encodeURIComponent(q)}`, UA, 30000);
    if (!ok) return [];
    return parseCards($, MAIN_URL);
  } catch {
    return [];
  }
}

function metaOf($: Cheerp, label: string): string | null {
  let out: string | null = null;
  $("strong").toArray().forEach((el) => {
    if (out) return;
    const t = squash($(el).text());
    if (t.toLowerCase().includes(label.toLowerCase())) {
      const parent = $(el).parent().text() || t;
      const v = squash(parent).replace(new RegExp(`^${t}\\s*:?`, "i"), "").trim();
      if (v) out = v;
    }
  });
  return out;
}

export async function movielinkbdLoad(url: string): Promise<Detail> {
  const { ok, $ } = await getDoc(url, UA, 30000);
  if (!ok) throw new Error(`mlbd load ${url} failed`);

  const title = squash($("h1").first().text()) || squash($("title").first().text());
  const og = $('meta[property="og:image"]').attr("content") || "";
  const poster = og || ($('img[src*="uploads"]').first().attr("src") ?? null);

  // plot + tags
  let plot: string | null = null;
  $("strong").toArray().some((el) => {
    if (squash($(el).text()).toLowerCase().startsWith("storyline")) {
      const p = $(el).closest("p");
      plot = squash(p.text().replace(/^Storyline:?\s*/i, "")) || null;
      return true;
    }
    return false;
  });

  const imdb = $('meta[property="og:description"]').attr("content")?.match(/IMDb[:\s-]*([\d.]+)/i)?.[1] ?? null;

  // embedded player encoded urls (movie + 1080p variant)
  let enc720: string | null = null;
  let enc1080: string | null = null;
  const html = $.html();
  const m720 = html.match(/currentVideoEnc\s*=\s*['"]([A-Za-z0-9+/=]{20,})['"]/);
  const m1080 = html.match(/currentVideo1080Enc\s*=\s*['"]([A-Za-z0-9+/=]{20,})['"]/);
  if (m720) enc720 = decodeEnc(m720[1]);
  if (m1080) enc1080 = decodeEnc(m1080[1]);

  // download buttons as fallback / extra
  const dlButtons: { label: string; href: string }[] = [];
  for (const a of $("a[href*='/generate/?file=']").toArray()) {
    const href = ($(a).attr("href") || "").trim();
    if (!href) continue;
    const label = squash($(a).text()).replace(/^Download\s*in\s*/i, "").trim();
    dlButtons.push({ label: label || "Download", href: href.startsWith("http") ? href : `${MAIN_URL}${href}` });
  }

  const isSeriesPost = isSeries(url, title);
  const type = isSeriesPost ? "TvSeries" : "Movie";

  if (type === "Movie") {
    const parts: string[] = [];
    if (enc720) parts.push(`720p|${enc720}`);
    if (enc1080 && enc1080 !== enc720) parts.push(`1080p|${enc1080}`);
    for (const b of dlButtons) parts.push(`${b.label}|${b.href}`);
    return {
      source: "movielinkbd", title, url, poster: poster || null,
      plot, tags: metaOf($, "Genres") ? [metaOf($, "Genres") as string] : null,
      year: Number(title.match(/\((\d{4})\)/)?.[1] ?? 0) || null,
      type, movieData: parts.join(" ; "), episodes: null,
      language: metaOf($, "Language"), contentQuality: metaOf($, "Quality"), imdb,
    };
  }

  // Series: first-episode embed; additional episodes probed by filename
  // pattern (Episode-NN) at loadLinks time via the CDN.
  const first = enc720 || enc1080 || "";
  const eps: Episode[] = [];
  if (first) {
    eps.push({ data: `720p|${first}`, name: "Episode 1", season: 1, episode: 1 });
  }
  for (const b of dlButtons) eps.push({ data: `${b.label}|${b.href}`, name: b.label, season: 1, episode: eps.length + 1 });
  return {
    source: "movielinkbd", title, url, poster: poster || null,
    plot, tags: null, year: Number(title.match(/\((\d{4})\)/)?.[1] ?? 0) || null,
    type, movieData: null, episodes: eps.length ? eps : null,
    language: metaOf($, "Language"), imdb,
  };
}

/**
 * /generate/?file=<b64> pages are a 10s-verify funnel that finally calls
 * https://dl.vircloud.site/api/sign/<b64> → { downloadUrl }. We skip the
 * funnel and hit the sign API directly (verified: works, 206 range-supporting
 * signed url). The download url requires a browser UA + vircloud referer.
 */
async function resolveGenerate(href: string): Promise<StreamLink | null> {
  try {
    const file = new URL(href).searchParams.get("file");
    if (!file) return null;
    const res = await getRaw(
      `https://dl.vircloud.site/api/sign/${file}`,
      {
        ...DOC_HEADERS,
        Referer: "https://new.vircloud.site/",
        Accept: "application/json",
      },
      15000
    );
    const j = JSON.parse(res.text) as { downloadUrl?: string };
    if (!j.downloadUrl || !j.downloadUrl.startsWith("http")) return null;
    return { name: "MovieLinkBD", label: "", url: j.downloadUrl, quality: 0, referer: "https://new.vircloud.site/" };
  } catch {
    return null;
  }
}

export async function movielinkbdLoadLinks(data: string): Promise<StreamLink[]> {
  const out: StreamLink[] = [];
  const seen = new Set<string>();
  for (const item of data.split(" ; ")) {
    const [qRaw, urlRaw] = item.includes("|") ? [item.split("|")[0], item.split("|").slice(1).join("|")] : ["", item];
    const quality = qRaw.replace(/_/g, "").trim();
    const link = (urlRaw || item).trim();
    if (!link.startsWith("http")) continue;
    if (seen.has(link)) continue;
    seen.add(link);

    if (link.includes("/generate/?file=") || link.includes("movielinkbd.is/generate")) {
      // resolve the verify-funnel into a real signed direct url; if the sign
      // api fails the link is dropped — unplayable page links confused users
      // ("1080p lekha dekhlam but play hoy nah")
      const q = qInt(quality) || qInt(decodeURIComponent(link));
      const resolved = await resolveGenerate(link);
      if (resolved) {
        out.push({
          ...resolved,
          label: `MLBD ${q ? q + "p " : ""}Backup`,
          quality: q,
        });
      }
      continue;
    }
    if (isDirectMediaUrl(link) || /cdn\.mlmbd\.lol|\.mkv($|\?)|\.mp4($|\?)/.test(link)) {
      const q = qInt(link) || qInt(quality);
      const qlabel = /(\d{3,4})p/.test(link) ? (/(\d{3,4})p/.exec(link)?.[0] ?? quality) : (quality || `${q || "Auto"}`);
      out.push({ name: "MovieLinkBD", label: `MLBD ${qlabel}`, url: link, quality: q, referer: `${MAIN_URL}/` });
    } else {
      // unknown non-media link with no resolver — skip instead of showing a
      // dead "page" row in the source menu
      continue;
    }
  }
  // de-dup by url, playable-first ordering handled by links-utils
  const uniq = out.filter((l, i, arr) => arr.findIndex((x) => x.url === l.url) === i);
  return uniq;
}
