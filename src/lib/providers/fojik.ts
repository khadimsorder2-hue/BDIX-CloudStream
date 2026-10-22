// Fojik.site (MLWBD-style Dooplay WordPress) — TS port.
// - Home: /movie/ + /genre/<slug>/, pagination /movie/page/N/
// - Search: /?s=<q>
// - Load: post page → title/poster/plot + FU download table
//   (rows: Quality | Language | Size | <form action="...blog.php"><input FU><input FN>)
// - Links: multi-hop protected chain:
//     1) POST FU/FN → blog.php           → form (sharelink-*/freethemesy dld2.php, FU2)
//     2) POST FU2    → dld2.php          → next form (FU2 again)
//     3) POST FU2    → freethemesy dld2  → page with var sss = '<b64>' + api path
//     4) POST s/v    → <origin>/new/l/api/m  → technews24.site/links/<b64> page
//     5) links page → go.php/go2.php?i=… page-links (final hop is CF-hardened)

import { getDoc, getRaw, postRaw, loadHtml, DOC_HEADERS, squash, qualityInt } from "../fetcher";
import type { SearchItem, HomeRow, Detail, StreamLink } from "./types";

const MAIN_URL = "https://fojik.site";
const BLOG_ACTION = "https://search.technews24.site/blog.php";

export const FOJIK_CATEGORIES: [string, string][] = [
  ["", "Latest Movies"],
  ["genre/action", "Action"],
  ["genre/thriller", "Thriller"],
  ["genre/comedy", "Comedy"],
  ["genre/horror", "Horror"],
  ["genre/romance", "Romance"],
  ["genre/drama", "Drama"],
  ["genre/science-fiction", "Sci-Fi"],
];

interface FojikRow {
  quality: string;
  lang: string;
  size: string;
  FU: string;
  FN: string;
  formAction: string;
}

function parseCards($: import("cheerio").CheerioAPI, base: string): SearchItem[] {
  const items: SearchItem[] = [];
  const seen = new Set<string>();
  // Dooplay cards: <article class="item movies"> <div class="poster"><img></div>
  //                <h3><a href="post">title</a></h3>
  for (const art of $("article.item").toArray()) {
    const a = $(art).find("h3 a[href], .data h3 a[href]").first();
    let href = ($(a).attr("href") || "").trim();
    let title = squash($(a).text());
    const img = $(art).find(".poster img").first();
    if (!href) {
      // fallback: anchor with title attr wrapping poster
      const a2 = $(art).find("a[href]").toArray().find((el) =>
        /^https?:\/\/fojik\.site\/(movie|tvshows|series)\/[^/]+\/$/.test($(el).attr("href") || ""));
      if (!a2) continue;
      href = $(a2).attr("href") as string;
      title = title || squash($(img).attr("alt") || "");
    }
    if (!/^https?:\/\/fojik\.site\/(movie|tvshows|series)\/[^/]+\/$/.test(href)) continue;
    if (seen.has(href)) continue;
    title = title || squash(img.attr("alt") || "");
    if (!title || title.length < 3) continue;
    seen.add(href);
    const poster =
      (img.attr("data-src") || img.attr("src") || (img.attr("srcset") || "").split(" ")[0] || "").trim() || null;
    const q = squash($(art).find(".quality, .beatxquality").first().text());
    items.push({
      title, url: href, poster,
      type: /(tvshows|series)/.test(href) ? "TvSeries" : "Movie",
      quality: q || null,
    });
  }
  // fallback: bare anchor+img pairs (search pages use this)
  if (!items.length) {
    for (const a of $("a[href]").toArray()) {
      const href = ($(a).attr("href") || "").trim();
      if (!/^https?:\/\/fojik\.site\/(movie|tvshows|series)\/[^/]+\/$/.test(href)) continue;
      if (seen.has(href)) continue;
      const img = $(a).find("img").first();
      const title = squash($(a).attr("title") || "") || squash(img.attr("alt") || "") || squash($(a).text());
      if (!title || title.length < 3) continue;
      seen.add(href);
      const poster =
        (img.attr("data-src") || img.attr("src") || (img.attr("srcset") || "").split(" ")[0] || "").trim() || null;
      items.push({
        title, url: href, poster,
        type: /(tvshows|series)/.test(href) ? "TvSeries" : "Movie",
        quality: null,
      });
    }
  }
  return items;
}

export async function fojikHome(category: string, page: number): Promise<HomeRow> {
  const path = category.replace(/^\/+|\/+$/g, "");
  const url = page <= 1
    ? path ? `${MAIN_URL}/${path}/` : `${MAIN_URL}/movie/`
    : path ? `${MAIN_URL}/${path}/page/${page}/` : `${MAIN_URL}/movie/page/${page}/`;
  try {
    const { ok, $ } = await getDoc(url, DOC_HEADERS, 30000);
    if (!ok) return { title: "", items: [], hasNext: false };
    const items = parseCards($, MAIN_URL);
    return { title: "", items, hasNext: items.length >= 10 };
  } catch {
    return { title: "", items: [], hasNext: false };
  }
}

export async function fojikSearch(q: string): Promise<SearchItem[]> {
  try {
    const { ok, $ } = await getDoc(`${MAIN_URL}/?s=${encodeURIComponent(q)}`, DOC_HEADERS, 30000);
    if (!ok) return [];
    return parseCards($, MAIN_URL);
  } catch {
    return [];
  }
}

function parseFuTable($: import("cheerio").CheerioAPI): FojikRow[] {
  const rows: FojikRow[] = [];
  $("form").toArray().forEach((f) => {
    const fu = $(f).find("input[name='FU']").attr("value") || $(f).find("input[name=\"FU\"]").attr("value");
    if (!fu) return;
    const fn = $(f).find("input[name='FN']").attr("value") || $(f).find("input[name=\"FN\"]").attr("value") || "";
    const action = $(f).attr("action") || BLOG_ACTION;
    const tr = $(f).closest("tr");
    const tds = tr.find("td").toArray().map((td) => squash($(td).text()));
    rows.push({
      quality: tds[1] || "",
      lang: tds[2] || "",
      size: tds[3] || "",
      FU: fu, FN: fn, formAction: action,
    });
  });
  return rows;
}

export async function fojikLoad(url: string): Promise<Detail> {
  const { ok, $ } = await getDoc(url, DOC_HEADERS, 30000);
  if (!ok) throw new Error(`fojik load ${url} failed`);

  const title = squash($("h1").first().text()) || squash($('meta[property="og:title"]').attr("content") || "");
  const poster = $('meta[property="og:image"]').attr("content") || ($("img.poster").first().attr("src") ?? null);
  const plot = squash($(".wp-content, .single-content p, #overview p").first().text()) ||
    $('meta[name="description"]').attr("content") || null;
  const tags = $(".genres-content a, .genres a").toArray().map((a) => squash($(a).text())).filter(Boolean).slice(0, 6);
  const year = Number(title.match(/\((\d{4})\)/)?.[1] ?? 0) || null;

  const rows = parseFuTable($);
  // data format: quality|FU@@formAction@@FN   (pipe separates quality; @@ separates chain fields)
  const movieData = rows
    .map((r) => `${r.quality || "Link"}|${r.FU}@@${r.formAction}@@${r.FN}`)
    .join(" ; ");

  return {
    source: "fojik", title, url, poster: poster || null, plot,
    tags: tags.length ? tags : null, year,
    type: /(tvshows|series|season)/.test(url) ? "TvSeries" : "Movie",
    movieData: rows.length ? movieData : null, episodes: null,
  };
}

/** generic: read first form (action + all hidden inputs) from an html body */
function firstForm(html: string): { action: string; fields: Record<string, string> } | null {
  const $ = loadHtml(html);
  const f = $("form").toArray().find((el) => $(el).find("input[name='FU2'], input[name='FU']").length);
  if (!f) return null;
  const fields: Record<string, string> = {};
  $(f).find("input").toArray().forEach((inp) => {
    const n = $(inp).attr("name");
    if (n) fields[n] = $(inp).attr("value") || "";
  });
  return { action: $(f).attr("action") || "", fields };
}

async function postForm(url: string, fields: Record<string, string>, referer: string, timeout = 25000) {
  const r = await postRaw(url, fields, { ...DOC_HEADERS, Referer: referer }, timeout);
  return { ...r, url: r.finalUrl };
}

export async function fojikLoadLinks(data: string): Promise<StreamLink[]> {
  const out: StreamLink[] = [];
  // multi-hop chain is expensive (~5 reqs per row) — take first 3 quality rows
  const items = data.split(" ; ").slice(0, 3);
  for (const item of items) {
    const pipe = item.indexOf("|");
    if (pipe < 0) continue;
    const quality = item.slice(0, pipe);
    const rest = item.slice(pipe + 1);
    const [FU, formAction, FN] = rest.split("@@");
    if (!FU) continue;

    try {
      // hop 1 — blog.php (FU + FN)
      let r = await postForm(formAction || BLOG_ACTION, { FU, FN }, `${MAIN_URL}/`);
      let form = firstForm(r.text);
      let guard = 0;
      // hops 2..n — dld2.php style relays carrying FU2
      while (form && form.action && form.fields.FU2 && guard++ < 6 &&
             !/var\s+sss\s*=/.test(r.text)) {
        const target = form.action.includes("://")
          ? form.action
          : new URL(form.action, new URL(r.url || form.action).origin).toString();
        r = await postForm(target, form.fields, r.url || form.action);
        form = firstForm(r.text);
      }

      // final api hop — page (e.g. freethemesy dld2) holds `var sss = '<b64>'`;
      // its JS POSTs {s: sss, v: token} to <origin>/new/l/api/m which responds
      // with the destination URL.
      const sss = /var\s+sss\s*=\s*'([^']+)'/.exec(r.text)?.[1];
      if (sss && r.url) {
        const origin = new URL(r.url).origin;
        const api = `${origin}/new/l/api/m`;
        const fr = await fetch(api, {
          method: "POST",
          headers: {
            ...DOC_HEADERS,
            Referer: r.url,
            "X-Requested-With": "XMLHttpRequest",
            "Content-Type": "application/x-www-form-urlencoded",
          },
          body: new URLSearchParams({ s: sss, v: "6ad99d6354708" }).toString(),
          signal: AbortSignal.timeout(20000),
        });
        const dest = (await fr.text()).trim();
        if (dest.startsWith("http")) {
          // links page → enumerate go.php / go2.php page links
          const lr = await getRaw(dest, { ...DOC_HEADERS, Referer: "https://freethemesy.shop/" }, 25000);
          if (lr.ok) {
            const links = [...lr.text.matchAll(/https:\/\/[a-z.]*technews24\.(?:site|me)\/go2?\.php\?i=[A-Za-z0-9+/=_-]+/g)]
              .map((m) => m[0]);
            const uniq = [...new Set(links)].slice(0, 12);
            if (uniq.length) {
              uniq.forEach((u, i) =>
                out.push({
                  name: "Fojik",
                  label: `${quality || "Link"}${i ? ` #${i + 1}` : ""}`,
                  url: u, quality: qualityInt(quality), referer: dest, isPage: true,
                })
              );
              continue;
            }
          }
          out.push({ name: "Fojik", label: `${quality || "Link"} Direct`, url: dest, quality: qualityInt(quality), referer: "https://freethemesy.shop/", isPage: true });
          continue;
        }
      }

      // chain unresolved → surface the first form target as a page link
      if (form && form.action) {
        out.push({
          name: "Fojik", label: `${quality || "Link"} Page`,
          url: form.action, quality: qualityInt(quality), referer: `${MAIN_URL}/`, isPage: true,
        });
      }
    } catch {
      // skip broken row
    }
  }
  // de-dup by url
  return out.filter((l, i, arr) => arr.findIndex((x) => x.url === l.url) === i);
}
