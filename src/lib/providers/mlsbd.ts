// MLSBD (mlsbd.co) — TS port of BDIX-CloudStream MlsbdProvider.kt

import { getDoc, getRaw, loadHtml, DOC_HEADERS, squash, qualityLabel, absUrl } from "../fetcher";
import type { Cheerp } from "../fetcher";
import type { SearchItem, HomeRow, Detail, Episode, StreamLink } from "./types";
import { dispatchHost, isHostLink, isDirectMediaUrl } from "./extractors";

const MAIN_URL = "https://mlsbd.co";

export const MLSBD_CATEGORIES: [string, string][] = [
  ["", "Latest Movies"],
  ["category/bangla-dubbed", "Bangla Dubbed"],
  ["category/dual-audio-movies", "Multi Audio Movies"],
  ["category/tv-series", "TV Series"],
  ["category/bollywood-movies", "Bollywood Movies"],
  ["category/bangla-movies", "Bengali Movies"],
  ["category/hollywood-movies", "Hollywood Movies"],
];

function isSeries(url: string, title: string): boolean {
  return url.includes("/tv-series/") ||
    /\bs\d{1,2}(?:e\d{1,3})?\b|season\s*\d|web.?series|\bepisode\b/i.test(title);
}

function parseCards($: Cheerp, base: string): SearchItem[] {
  const items: SearchItem[] = [];
  for (const post of $("div.single-post").toArray()) {
    const title = squash($(post).find(".post-title").text()) ||
      squash($(post).find("h2").text());
    if (!title) continue;
    const a = $(post).find(".thumb > a").first();
    if (!a.length) continue;
    const href = absUrl($, a.get(0) as never, base);
    if (!href.startsWith("http")) continue;
    const img = $(post).find(".thumb img").first();
    const poster = img.length
      ? (img.attr("src") || img.attr("data-src") || (img.attr("srcset") || "").split(" ")[0] || "").trim() || null
      : null;
    items.push({
      title,
      url: href,
      poster,
      type: isSeries(href, title) ? "TvSeries" : "Movie",
    });
  }
  const seen = new Set<string>();
  return items.filter((i) => (seen.has(i.url) ? false : (seen.add(i.url), true)));
}

export async function mlsbdHome(category: string, page: number): Promise<HomeRow> {
  const path = category.replace(/^\/+|\/+$/g, "");
  const url = page <= 1
    ? path ? `${MAIN_URL}/${path}/` : `${MAIN_URL}/`
    : path ? `${MAIN_URL}/${path}/page/${page}/` : `${MAIN_URL}/page/${page}/`;
  try {
    const { ok, $ } = await getDoc(url, DOC_HEADERS, 30000);
    if (!ok) return { title: "", items: [], hasNext: false };
    const items = parseCards($, MAIN_URL);
    return { title: "", items, hasNext: items.length > 0 };
  } catch {
    return { title: "", items: [], hasNext: false };
  }
}

export async function mlsbdSearch(q: string): Promise<SearchItem[]> {
  const query = q.trim();
  if (!query) return [];
  try {
    const { ok, $ } = await getDoc(`${MAIN_URL}/?s=${encodeURIComponent(query)}`, DOC_HEADERS, 30000);
    if (!ok) return [];
    return parseCards($, MAIN_URL);
  } catch {
    return [];
  }
}

/** "Download in 720p quality" / class sd|hd|hevc -> quality label */
function qualityLabelOf(text: string, classes: string[]): string {
  const t = text.toLowerCase();
  const q = qualityLabel(text);
  if (q) return q;
  if (classes.includes("hevc")) return "HEVC";
  if (classes.includes("hd")) return "HD";
  if (classes.includes("sd")) return "SD";
  return t.includes("4k") ? "4K" : "Unknown";
}

interface QLink { quality: string; url: string }

export async function mlsbdLoad(url: string): Promise<Detail> {
  const { ok, $ } = await getDoc(url, DOC_HEADERS, 45000);
  if (!ok) throw new Error("post unreachable: " + url);

  const title = squash($(".name").text()) ||
    squash($('meta[property="og:title"]').attr("content") || "") ||
    squash($("h1").text()) || url;
  const year = Number(/\((\d{4})\)/.exec(title)?.[1] ?? 0) || null;
  const image = ($("img.aligncenter").first().attr("src") ||
    $('meta[property="og:image"]').attr("content") || "").trim() || null;

  const plotParts = [
    squash($(".single-post-title").text()),
    squash($(".misc").text()),
    squash($(".details").text()),
    squash($(".storyline").text()),
    squash($(".production").text()),
    squash($(".media").text()),
  ].filter(Boolean);
  const plot = plotParts.join("\n") ||
    squash($('meta[name="description"]').attr("content") || "") || null;
  const tags = [...new Set($("a[rel=tag]").map((_, el) => squash($(el).text())).get())]
    .filter(Boolean).slice(0, 6);

  // Download sections in document order.
  const sections = new Map<string, QLink[]>();
  for (const div of $("div.post-section-title.download, div.post-section-title").toArray()) {
    const secTitle = squash($(div).text()) || "Download Now";
    const bucket = sections.get(secTitle) ?? [];
    let sib = $(div).next();
    while (sib.length && sib.get(0)?.tagName === "p") {
      for (const a of sib.find("a").toArray()) {
        const cls = ($(a).attr("class") || "").split(/\s+/);
        if (!cls.some((c) => c === "sd" || c === "hd" || c === "hevc")) continue;
        const href = absUrl($, a as never, url).trim();
        if (!href.startsWith("http")) continue;
        if (!bucket.some((b) => b.url === href)) {
          bucket.push({ quality: qualityLabelOf($(a).text(), cls), url: href });
        }
      }
      sib = sib.next();
    }
    sections.set(secTitle, bucket);
  }

  // Resilience: page-wide sd/hd/hevc anchors.
  if ([...sections.values()].every((v) => v.length === 0)) {
    const bucket: QLink[] = [];
    for (const a of $("a.sd, a.hd, a.hevc").toArray()) {
      const href = absUrl($, a as never, url).trim();
      if (href.startsWith("http") && !bucket.some((b) => b.url === href)) {
        bucket.push({ quality: qualityLabelOf($(a).text(), ($(a).attr("class") || "").split(/\s+/)), url: href });
      }
    }
    sections.set("Download Now", bucket);
  }

  const epRegex = /Epi-?\s*(\d{1,3})(?:-(\d{1,3}))?/i;
  const epSectionCount = [...sections.keys()].filter((k) => epRegex.test(k)).length;
  const isSeriesPost = isSeries(url, title) || epSectionCount >= 2;

  if (isSeriesPost) {
    const episodes: Episode[] = [];
    for (const [secTitle, links] of sections) {
      if (!links.length) continue;
      const m = epRegex.exec(secTitle);
      const start = Number(m?.[1] ?? 0) || 1;
      const end = Number(m?.[2] ?? start) || start;
      const data = links.map((l) => `${l.quality}|${l.url}`).join(" ; ");
      const qualities = [...new Set(links.map((l) => /(\d{3,4})p/.exec(l.quality)?.[1]).filter(Boolean))].join("/");
      for (let ep = start; ep <= end; ep++) {
        episodes.push({
          data,
          name: `Episode ${ep}${qualities ? ` · ${qualities}` : ""}`,
          season: 1,
          episode: ep,
          qualities,
        });
      }
    }
    episodes.sort((a, b) => (a.episode ?? 0) - (b.episode ?? 0));
    return {
      source: "mlsbd", title, url, poster: image, plot, tags: tags.length ? tags : null,
      year, type: "TvSeries", movieData: null, episodes,
    };
  }

  const all = [...sections.values()].flat()
    .filter((l, i, arr) => arr.findIndex((x) => x.url === l.url) === i);
  return {
    source: "mlsbd", title, url, poster: image, plot, tags: tags.length ? tags : null,
    year, type: "Movie",
    movieData: all.map((l) => `${l.quality}|${l.url}`).join(" ; "),
    episodes: null,
  };
}

export async function mlsbdLoadLinks(data: string): Promise<StreamLink[]> {
  const out: StreamLink[] = [];
  const counted = new Set<string>();
  const seen = new Set<string>();

  const push = (links: StreamLink[], hostKey: string) => {
    if (links.length && !counted.has(hostKey)) counted.add(hostKey);
    out.push(...links);
  };

  for (const item of data.split(" ; ")) {
    const link = item.includes("|") ? item.split("|").slice(1).join("|").trim() : item.trim();
    if (!link.startsWith("http")) continue;

    try {
      if (link.includes("savelinks")) {
        const res = await getRaw(link, { ...DOC_HEADERS, Referer: `${MAIN_URL}/` }, 45000);
        const ok = res.ok, finalUrl = res.finalUrl;
        const $ = loadHtml(res.text);
        if (!ok) continue;
        for (const a of $("ul li a[href], a[href]").toArray()) {
          const host = ($(a).attr("href") || "").replace(/&amp;/g, "&").trim();
          if (!isHostLink(host) || seen.has(host)) continue;
          seen.add(host);
          if (isDirectMediaUrl(host)) {
            push([{ name: "MLSBD", label: "Direct", url: host, quality: 0, referer: `${MAIN_URL}/` }], host);
          } else {
            push(await dispatchHost(host, finalUrl), host);
          }
        }
      } else if (isDirectMediaUrl(link) && !/gdxfiles|gdflix|multicloud|filepress|hubcloud|hubdrive|filesdl|shikshakdaak|gofile|m4ulinks/.test(link)) {
        push([{ name: "MLSBD", label: "Direct", url: link, quality: 0, referer: `${MAIN_URL}/` }], link);
      } else {
        push(await dispatchHost(link, `${MAIN_URL}/`), link);
      }
    } catch (e) { if (process.env.DEBUG_LINKS) console.log("[mlsbd catch]", String(e)); }
  }
  return out;
}
