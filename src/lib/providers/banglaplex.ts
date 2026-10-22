// BanglaPlex (banglaplex.biz) — TS port of the CloudStream BanglaPlexProvider.
// - Home:  POST /filter_movies/<page>  (action=fetch_data&sort=<key>&page=<n>)
// - Search: GET /home/autocompleteajax?term=<q>  (JSON array)
// - Load:  /watch/<slug>.html → iframe.responsive-embed-item (embed host)
// - Links: embed page holds MasterJS = '<base64 AES payload>';
//          PBKDF2-SHA512 key "4VqE3#N7zt&HEP^a" → AES-CBC → player config
//          (sources[0].file = m3u8, tracks = subtitles).

import { getDoc, getRaw, postRaw, loadHtml, DOC_HEADERS, squash } from "../fetcher";
import type { SearchItem, HomeRow, Detail, StreamLink } from "./types";

const MAIN_URL = "https://banglaplex.biz";
const AES_KEY = "4VqE3#N7zt&HEP^a";

export const BANGLAPLEX_CATEGORIES: [string, string][] = [
  ["total_view", "Trending Movies"],
  ["rating", "Best Rated"],
  ["release", "Recent Movies"],
  ["rand", "Random"],
  ["az", "All Movies"],
];

interface AESData {
  salt: string;
  iterations: string;
  ciphertext: string;
  iv: string;
}

function hexToBytes(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

/** CryptoJS-compatible AES-CBC decrypt with PBKDF2-SHA512 (OpenSSL KDF, MD5 not used here) */
async function cryptoAesDecrypt(data: AESData, pass: string): Promise<string> {
  const subtle = (globalThis.crypto as Crypto).subtle;
  const baseKey = await subtle.importKey(
    "raw",
    new TextEncoder().encode(pass),
    "PBKDF2",
    false,
    ["deriveKey"]
  );
  const key = await subtle.deriveKey(
    {
      name: "PBKDF2",
      salt: hexToBytes(data.salt) as BufferSource,
      iterations: Number(data.iterations) || 1,
      hash: "SHA-512",
    },
    baseKey,
    { name: "AES-CBC", length: 256 },
    false,
    ["decrypt"]
  );
  const ct = hexToBytes(data.ciphertext);
  const iv = hexToBytes(data.iv);
  const plain = await subtle.decrypt(
    { name: "AES-CBC", iv: iv as BufferSource },
    key,
    ct as BufferSource
  );
  return new TextDecoder().decode(plain);
}

async function filterMovies(sort: string, page: number): Promise<string> {
  const body = {
    action: "fetch_data",
    minimum_rating: "0",
    maximum_rating: "10",
    sort,
    page: String(page),
  };
  const res = await postRaw(
    `${MAIN_URL}/filter_movies/${page}`,
    body,
    {
      ...DOC_HEADERS,
      Referer: `${MAIN_URL}/movies.html`,
      Origin: MAIN_URL,
    },
    25000
  );
  const text = res.text;
  try {
    const j = JSON.parse(text) as { movie_list?: string };
    return (j.movie_list || "").replace(/\\r/g, "").replace(/\\n/g, "").replace(/\\"/g, "");
  } catch {
    return text;
  }
}

function parseFilterCards(html: string): SearchItem[] {
  const $ = loadHtml(html);
  const items: SearchItem[] = [];
  const seen = new Set<string>();
  for (const card of $("div.col-md-3, div.col-sm-4").toArray()) {
    const a = $(card).find("a[href*='/watch/'], a.ico-play").first();
    const href = ($(a).attr("href") || "").trim();
    if (!href.includes("/watch/")) continue;
    const title =
      squash($(card).find(".movie-title h3 a, .movie-title h3, h3 a, h3").first().text()) ||
      squash($(a).attr("title") || "");
    if (!title) continue;
    if (seen.has(href)) continue;
    seen.add(href);
    const style = $(card).find(".latest-movie-img-container").first().attr("style") || "";
    const pm = /url\(['"]?([^'")]+)/.exec(style);
    const quality = squash($(card).find(".video_quality_movie span.label").first().text());
    items.push({
      title,
      url: href.startsWith("http") ? href : `${MAIN_URL}${href}`,
      poster: pm ? (pm[1].startsWith("http") ? pm[1] : `${MAIN_URL}${pm[1]}`) : null,
      type: "Movie",
      quality: quality || null,
    });
  }
  return items;
}

export async function banglaplexHome(category: string, page: number): Promise<HomeRow> {
  try {
    const html = await filterMovies(category || "total_view", page);
    const items = parseFilterCards(html);
    return { title: "", items, hasNext: items.length >= 10 };
  } catch {
    return { title: "", items: [], hasNext: false };
  }
}

interface BpSearchRow {
  title: string;
  type: string;
  image: string;
  url: string;
}

export async function banglaplexSearch(q: string): Promise<SearchItem[]> {
  try {
    const res = await getRaw(`${MAIN_URL}/home/autocompleteajax?term=${encodeURIComponent(q)}`, {
      ...DOC_HEADERS, Referer: `${MAIN_URL}/movies.html`, "X-Requested-With": "XMLHttpRequest",
    }, 20000);
    const text = res.text;
    let rows: BpSearchRow[] = [];
    try {
      rows = JSON.parse(text) as BpSearchRow[];
    } catch {
      return [];
    }
    return rows
      .filter((r) => r.url)
      .map((r) => ({
        title: squash(r.title),
        url: r.url.startsWith("http") ? r.url : `${MAIN_URL}${r.url}`,
        poster: r.image ? (r.image.startsWith("http") ? r.image : `${MAIN_URL}${r.image}`) : null,
        type: /series|episode/i.test(r.type || "") ? ("TvSeries" as const) : ("Movie" as const),
      }));
  } catch {
    return [];
  }
}

export async function banglaplexLoad(url: string): Promise<Detail> {
  const { ok, $ } = await getDoc(url, DOC_HEADERS, 30000);
  if (!ok) throw new Error(`banglaplex load ${url} failed`);

  const title = squash($("h1").first().text()) || url.split("/").pop()?.replace(".html", "") || "BanglaPlex";
  const poster = ($("img.img-responsive").first().attr("src") ||
    $('meta[property="og:image"]').attr("content") || null) as string | null;
  const plot = squash($(".col-md-9 > div.row > div.col-md-12 > p:nth-child(5)").first().text()) ||
    $('meta[name="description"]').attr("content") || null;
  const tags = $("div.col-md-6 > p > a").toArray().map((a) => squash($(a).text())).filter(Boolean).slice(0, 6);
  const year = Number(title.match(/\((\d{4})\)/)?.[1] ?? 0) || null;

  const iframeSrc = $("iframe.responsive-embed-item").first().attr("src") ||
    $("iframe[src*='embed']").first().attr("src") || "";
  const data = `${url},${iframeSrc}`;

  return {
    source: "banglaplex", title, url, poster, plot,
    tags: tags.length ? tags : null, year,
    type: "Movie", movieData: iframeSrc ? data : null, episodes: null,
  };
}

export async function banglaplexLoadLinks(data: string): Promise<StreamLink[]> {
  const out: StreamLink[] = [];
  const [pageUrl, embed] = data.split(",");
  if (!embed || !embed.startsWith("http")) return out;

  // strategy 0 — embed page itself is a direct stream (rare)
  if (/\.(m3u8|mp4)(\?|$)/.test(embed)) {
    out.push({
      name: "BanglaPlex", label: "Auto", url: embed,
      quality: /1080/.test(embed) ? 1080 : /720/.test(embed) ? 720 : 0,
      referer: `${MAIN_URL}/`,
    });
    return out;
  }

  const res = await getRaw(embed, { ...DOC_HEADERS, Referer: `${MAIN_URL}/` }, 25000);

  // strategy 1 — old MasterJS AES format (banglaplex.fun era)
  const master = /MasterJS\s*=\s*'([^']+)/.exec(res.text)?.[1];
  if (master) {
    try {
      const payload = JSON.parse(atob(master)) as AESData;
      const cfg = await cryptoAesDecrypt(payload, AES_KEY);
      const file = /sources:\s*\[\{"file":"([^"]+)"/.exec(cfg)?.[1];
      const tracks = /tracks:\s*\[(.+)]/.exec(cfg)?.[1];
      if (file) {
        const link: StreamLink = {
          name: "BanglaPlex",
          label: /1080/.test(file) ? "1080p" : /720/.test(file) ? "720p" : "Auto",
          url: file.replace(/\\/g, ""),
          quality: /1080/.test(file) ? 1080 : /720/.test(file) ? 720 : 0,
          referer: embed,
        };
        if (tracks) {
          try {
            const list = JSON.parse(`[${tracks.replace(/\\/g, "")}]`) as { kind?: string; label?: string; file?: string }[];
            const caps = list.filter((t) => t.kind === "captions" && t.file);
            if (caps.length) (link as StreamLink & { subtitles?: string[] }).subtitles = caps.map((c) => c.file as string);
          } catch { /* optional */ }
        }
        out.push(link);
        return out;
      }
    } catch { /* fall through */ }
  }

  // strategy 2 — plextream-style multi-server popup: try each server page
  // for a plain m3u8/mp4 or on-page MasterJS config.
  const servers = [...new Set([...res.text.matchAll(/changeServer\(['"]([^'"]+)['"]/g)].map((m) => m[1]))].slice(0, 3);
  for (const srv of servers) {
    if (/abyssplayer|abyss\.to/.test(srv)) continue; // heavy ad-gate, skip
    try {
      const frag = srv.includes("#") ? srv.split("#")[1] : "";
      const base = srv.split("#")[0];
      const sr = await getRaw(base, { ...DOC_HEADERS, Referer: embed }, 20000);
      if (!sr.ok) continue;
      const m3u8 = /['"](https?:\/\/[^'"]+\.m3u8[^'"]*)['"]/.exec(sr.text)?.[1];
      if (m3u8) {
        out.push({ name: "BanglaPlex", label: frag ? `Server (${frag})` : "Server", url: m3u8, quality: 0, referer: base });
        break;
      }
      const master2 = /MasterJS\s*=\s*'([^']+)/.exec(sr.text)?.[1];
      if (master2) {
        const payload = JSON.parse(atob(master2)) as AESData;
        const cfg = await cryptoAesDecrypt(payload, AES_KEY);
        const file = /sources:\s*\[\{"file":"([^"]+)"/.exec(cfg)?.[1];
        if (file) {
          out.push({ name: "BanglaPlex", label: frag ? `Server (${frag})` : "Server", url: file.replace(/\\/g, ""), quality: 0, referer: base });
          break;
        }
      }
    } catch { /* next server */ }
  }

  if (out.length) return out;

  // strategy 3 — keep the embed reachable as a page link (player failover)
  out.push({
    name: "BanglaPlex", label: "Embed",
    url: embed, quality: 0, referer: `${MAIN_URL}/`, isPage: true,
  });
  void pageUrl;
  return out;
}
