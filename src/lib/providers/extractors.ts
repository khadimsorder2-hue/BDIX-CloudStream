// Host extractors — TS ports of BDIX-CloudStream Mlsbd/Extractors.kt
// (GDFlix, GDxFiles, MultiCloudLinks, HubCloud/Shikshakdaak, FilePress,
//  Gofile, Filesdl, M4ulinks, Pixeldrain). Each resolver returns StreamLink[].

import { getRaw, getDoc, getJson, DESKTOP_UA, qualityInt, loadHtml, squash } from "../fetcher";
import type { StreamLink } from "./types";

const RX_R2 = /https:\/\/pub-[A-Za-z0-9-]+\.r2\.dev\/[^"'<>\s]+/g;
const RX_FASTCDN = /https:\/\/fastcdn-dl\.pages\.dev\/\?url=[^"'<>\s]+/gi;
const RX_MULTIDL = /https:\/\/dr\d*\.multidownload\.lol\/d\/[^"'<>\s]+/gi;

function clean(u: string) {
  return u.replace(/&amp;/g, "&").trim();
}

function qNum(text: string): number {
  return qualityInt(text.slice(0, 20000));
}

function link(
  name: string, label: string, url: string, quality: number, referer: string, isPage = false
): StreamLink {
  return { name, label, url: clean(url), quality, referer, isPage };
}

function baseUrlOf(url: string): string {
  try { return new URL(url).origin; } catch { return ""; }
}

async function resolveRedirectLocation(url: string, referer: string): Promise<string | null> {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), 30000);
  try {
    const res = await fetch(url, {
      headers: { "User-Agent": DESKTOP_UA, Referer: referer },
      redirect: "manual",
      signal: ctrl.signal,
    });
    const loc = res.headers.get("location");
    return loc && loc.startsWith("http") ? clean(loc) : null;
  } catch {
    return null;
  } finally {
    clearTimeout(t);
  }
}

// ------------------------------------------------------------------ pixeldrain

export async function resolvePixeldrain(url: string, q: number, prefix = ""): Promise<StreamLink[]> {
  const id = url.split("pixeldrain.com/")[1]?.replace(/^\/+/, "").split("?")[0].split("/")[0] ?? "";
  if (!id || id.startsWith("api")) return [];
  return [link("pixeldrain", `${prefix}Pixeldrain`.trim(),
    `https://pixeldrain.com/api/file/${id}?download`, q, "https://pixeldrain.com/")];
}

// --------------------------------------------------------------------- GDFlix

export async function resolveGdflix(rawUrl: string, referer: string): Promise<StreamLink[]> {
  const out: StreamLink[] = [];
  try {
    const { ok, text, finalUrl } = await getRaw(rawUrl, { "User-Agent": DESKTOP_UA, Referer: referer }, 45000);
    if (!ok) return out;
    const html = text.replace(/&amp;/g, "&");
    const pageUrl = finalUrl;
    const $ = loadHtml(html);
    const fileName = squash(
      $("ul > li.list-group-item:contains(Name)").text().split("Name :")[1] ?? ""
    ) || $.root().find("title").text().split("|")[1]?.trim() || "";
    const fileSize = squash($("ul > li.list-group-item:contains(Size)").text().split("Size :")[1] ?? "");
    const q = qNum(`${fileName} ${html.slice(0, 8000)}`);
    const sizeTag = fileSize ? `[${fileSize}]` : "";
    const relTag = fileName.replace(/\.[a-z0-9]{2,4}$/i, "").slice(0, 40);
    const base = `${relTag ? relTag + " " : ""}${sizeTag}`.trim();

    for (const el of $("div.text-center a[href], a.btn[href]").toArray()) {
      const text = ($(el).text() || "").trim();
      const href = clean($(el).attr("href") || "");
      if (!href.startsWith("http")) continue;
      const t = text.toLowerCase();
      if (t.includes("direct dl")) {
        out.push(link("gdflix", `GDFlix[Direct] ${base}`.trim(), href, q, pageUrl));
      } else if (t.includes("cloud download [r2]") || (href.includes("url=") && href.includes("fastcdn"))) {
        const target = decodeURIComponent(href.split("url=")[1] ?? "");
        if (target.startsWith("http")) out.push(link("gdflix r2", `GDFlix R2 ${base}`.trim(), target, q, pageUrl));
      } else if (t.includes("pixeldra") || t.includes("pixel")) {
        const final = /download/i.test(href) ? href : `${baseUrlOf(href)}/api/file/${href.split("/").pop()}?download`;
        out.push(link("pixeldrain", `GDFlix Pixeldrain ${base}`.trim(), final, q, "https://pixeldrain.com/"));
      }
    }
    const r2 = [...new Set([...html.matchAll(RX_R2)].map((m) => clean(m[0])))].slice(0, 2);
    for (const u of r2) out.push(link("r2 direct", `GDFlix R2 ${base}`.trim(), u, q, pageUrl));
    const fast = [...new Set([...html.matchAll(RX_FASTCDN)].map((m) => clean(m[0])))].slice(0, 1);
    for (const u of fast) out.push(link("fastcdn", `FastCDN ${base}`.trim(), u, q, pageUrl));
  } catch (e) { if (process.env.DEBUG_LINKS) console.log("[extractor catch]", String(e)); }
  return out;
}

// ------------------------------------------------------------------- GDxFiles

export async function resolveGdxFiles(rawUrl: string, referer: string): Promise<StreamLink[]> {
  const out: StreamLink[] = [];
  const mainUrl = "https://gdxfiles.com";
  try {
    const { ok, text, finalUrl } = await getRaw(rawUrl, { "User-Agent": DESKTOP_UA, Referer: referer }, 45000);
    if (!ok) return out;
    const html = text.replace(/&amp;/g, "&");
    const pageUrl = finalUrl;
    const code = /\/file\/([A-Za-z0-9]{4,})/.exec(pageUrl)?.[1] ?? /\/file\/([A-Za-z0-9]{4,})/.exec(rawUrl)?.[1];
    if (!code) return out;
    const hash = /[?&]hash=([a-f0-9]{8,})/.exec(pageUrl)?.[1] ?? /[?&]hash=([a-f0-9]{8,})/.exec(html)?.[1];
    const q = qNum(html);
    let emitted = 0;

    if (hash) {
      const loc = await resolveRedirectLocation(`${mainUrl}/file/r2/${code}/download?hash=${hash}`, pageUrl);
      if (loc && /r2\.dev|cloudflarestorage\.com|pixeldrain/.test(loc)) {
        out.push(link("gdx r2", "GDxFiles R2", loc, q, `${mainUrl}/`));
        emitted++;
      }
      if (emitted === 0) {
        const px = await resolveRedirectLocation(`${mainUrl}/file/pixeldrain/${code}/download?hash=${hash}`, pageUrl);
        if (px?.includes("pixeldrain")) {
          const id = px.split("file/")[1]?.split("?")[0].replace(/^\/+|\/+$/g, "");
          if (id) {
            out.push(link("pixeldrain", "GDxFiles Pixeldrain",
              `https://pixeldrain.com/api/file/${id}?download`, q, "https://pixeldrain.com/"));
            emitted++;
          }
        }
      }
    }
    if (emitted === 0) {
      const r2 = [...new Set([...html.matchAll(RX_R2)].map((m) => clean(m[0])))].slice(0, 2);
      for (const u of r2) { out.push(link("gdx r2", "GDxFiles R2", u, q, pageUrl)); emitted++; }
    }
  } catch (e) { if (process.env.DEBUG_LINKS) console.log("[extractor catch]", String(e)); }
  return out;
}

// --------------------------------------------------------------- MultiCloud

export async function resolveMulticloud(rawUrl: string, referer: string): Promise<StreamLink[]> {
  const out: StreamLink[] = [];
  try {
    const { ok, text, finalUrl } = await getRaw(rawUrl, { "User-Agent": DESKTOP_UA, Referer: referer }, 45000);
    if (!ok) return out;
    const html = text.replace(/&amp;/g, "&");
    const pageUrl = finalUrl;
    const q = qNum(html);

    const playerLink = /https?:\/\/[a-z0-9.-]*multicloudlinks\.com\/player\.php\/?\?v=[A-Za-z0-9]+/i.exec(html)?.[0];
    if (playerLink) {
      try {
        const pres = await getRaw(playerLink, { "User-Agent": DESKTOP_UA, Referer: pageUrl }, 45000);
        const phtml = pres.text.replace(/&amp;/g, "&");
        const streamRx = new RegExp('streamSrc\\s*=\\s*"([^"]+)"');
        const mdRx = new RegExp('"(https?:[^"]*multidownload\\.lol[^"]*)"');
        const src = streamRx.exec(phtml)?.[1] ?? mdRx.exec(phtml)?.[1];
        if (src) out.push(link("multicloud", "MultiCloud Stream", src, q, playerLink));
      } catch { /* keep collected */ }
    }
    const dl = [...new Set([...html.matchAll(RX_MULTIDL)].map((m) => clean(m[0])))].slice(0, 1);
    for (const u of dl) {
      out.push(link("multicloud", "MultiCloud Direct", u.includes("action=") ? u : `${u}&action=download`, q, pageUrl));
    }
  } catch (e) { if (process.env.DEBUG_LINKS) console.log("[extractor catch]", String(e)); }
  return out;
}

// ------------------------------------------- HubCloud / Shikshakdaak shared

export async function extractHubCloudPage(rawUrl: string, referer: string, sourceName: string): Promise<StreamLink[]> {
  const out: StreamLink[] = [];
  try {
    const uri = new URL(rawUrl);
    const origin = uri.origin;
    let href = rawUrl;
    if (!rawUrl.includes("hubcloud.php") && sourceName === "Hub-Cloud") {
      const { $ } = await getDoc(rawUrl, { "User-Agent": DESKTOP_UA, Referer: referer }, 45000);
      const raw = $("#download").attr("href") ??
        $("a[href]").toArray().map((el) => $(el).attr("href") || "")
          .find((h) => h.includes("hubcloud.php") || h.includes("gamerxyt.com")) ?? "";
      href = raw.startsWith("http") ? raw : raw ? `${origin}/${raw.replace(/^\/+/, "")}` : "";
    }
    if (!href) return out;

    const { ok, text, finalUrl } = await getDoc(href, { "User-Agent": DESKTOP_UA, Referer: referer }, 60000);
    if (!ok) return out;
    const html = text.replace(/&amp;/g, "&");
    const $ = loadHtml(html);
    const pageUrl = finalUrl;
    const size = squash($("#size").text() || "");
    const header = squash($("div.card-header").text() || "").replace(/\.[a-z0-9]{2,4}$/i, "");
    const q = qNum(`${header} ${html.slice(0, 6000)}`);
    const extras = `${header ? `[${header.slice(0, 40)}]` : ""}${size ? `[${size}]` : ""}`;

    const seen = new Set<string>();
    for (const el of $("a[href], a[hx-redirect], button[hx-redirect]").toArray()) {
      const hx = $(el).attr("hx-redirect") || "";
      const link0 = $(el).attr("href") || "";
      let u = hx.startsWith("http") ? hx : link0.startsWith("http") ? link0 : "";
      if (!u || !seen.add(u)) continue;
      // jsoup el.text() equivalence: element's own text (incl. descendants)
      // only — parent() pulls in sibling buttons and pollutes matching.
      const t = ($(el).text() || "").toLowerCase();

      if (t.includes("fsl server") || t.includes("fslv2")) {
        out.push(link(sourceName, `${sourceName} [FSL] ${extras}`, u, q, pageUrl));
      } else if (t.includes("download file")) {
        out.push(link(sourceName, `${sourceName} ${extras}`, u, q, pageUrl));
      } else if (t.includes("buzzserver") || t.includes("buzz")) {
        const dlink = await resolveRedirectLocation(`${u}/download`, u);
        if (dlink) out.push(link(sourceName, `${sourceName} [BuzzServer] ${extras}`, dlink, q, u));
      } else if (t.includes("10gbps") || u.includes("gpdl")) {
        out.push(link(sourceName, `${sourceName} [10Gbps] ${extras}`, u, q, pageUrl));
      } else if (u.includes("pixeldrain") || t.includes("pixel")) {
        const final = u.includes("download") || u.includes("/api/file/") ? u : `${baseUrlOf(u)}/api/file/${u.split("/").pop()}?download`;
        out.push(link("pixeldrain", `${sourceName} Pixeldrain ${extras}`, final, q, "https://pixeldrain.com/"));
      } else if (t.includes("s3 server") || t.includes("s3")) {
        out.push(link(sourceName, `${sourceName} [S3 Server] ${extras}`, u, q, pageUrl));
      } else if (t.includes("mega")) {
        out.push(link(sourceName, `${sourceName} [Mega Server] ${extras}`, u, q, pageUrl));
      } else if (/\.(mkv|mp4|avi)(\?|$)/.test(u)) {
        out.push(link(sourceName, `${sourceName} ${extras}`, u, q, pageUrl));
      }
    }
  } catch (e) { if (process.env.DEBUG_LINKS) console.log("[extractor catch]", String(e)); }
  return out;
}

// ----------------------------------------------------------------- FilePress

export async function resolveFilePress(rawUrl: string, referer: string): Promise<StreamLink[]> {
  const out: StreamLink[] = [];
  try {
    const { ok, text, finalUrl } = await getRaw(rawUrl, { "User-Agent": DESKTOP_UA, Referer: referer }, 45000);
    if (!ok) return out;
    const html = text.replace(/&amp;/g, "&");
    const q = qNum(html);
    const direct = [...new Set(
      [...html.matchAll(
        /https?:\/\/[^"'<>\s]*(?:r2\.dev|busycdn\.xyz|cloudflarestorage\.com|multidownload\.lol|pixeldrain\.com)\/[^"'<>\s]*/g
      )].map((m) => clean(m[0]))
    )].slice(0, 3);
    for (const d of direct) out.push(link("filepress", "FilePress", d, q, finalUrl));
  } catch (e) { if (process.env.DEBUG_LINKS) console.log("[extractor catch]", String(e)); }
  return out;
}

// -------------------------------------------------------------------- Gofile

export async function resolveGofile(rawUrl: string): Promise<StreamLink[]> {
  const out: StreamLink[] = [];
  try {
    const id = /(?:\?c=|d\/)([\da-zA-Z-]+)/.exec(rawUrl)?.[1];
    if (!id) return out;
    const api = "https://api.gofile.io";
    const acc = await getRaw(api + "/accounts", { "User-Agent": DESKTOP_UA, "Content-Type": "application/json" }, 30000);
    // POST semantics via fetch directly
    const accRes = await fetch(api + "/accounts", { method: "POST" });
    const accJson = await accRes.json() as { data?: { token?: string } };
    const token = accJson?.data?.token;
    if (!token) return out;
    const globalJs = (await getRaw("https://gofile.io/dist/js/global.js", { "User-Agent": DESKTOP_UA }, 30000)).text;
    const wt = /appdata\.wt\s*=\s*["']([^"']+)["']/.exec(globalJs)?.[1];
    if (!wt) return out;
    const content = await getJson<{ data: { children: Record<string, { link?: string; name?: string; size?: number }> } }>(
      `${api}/contents/${id}?wt=${wt}`,
      { "User-Agent": DESKTOP_UA, Authorization: `Bearer ${token}` }, 30000
    );
    const children = content?.data?.children;
    if (!children) return out;
    const first = Object.values(children)[0];
    if (!first?.link) return out;
    const size = first.size ? (first.size < 1024 ** 3
      ? `${(first.size / 1024 ** 2).toFixed(2)} MB` : `${(first.size / 1024 ** 3).toFixed(2)} GB`) : "";
    const q = qNum(first.name ?? "");
    out.push({ name: "Gofile", label: `Gofile${size ? ` [${size}]` : ""}`, url: first.link, quality: q, referer: "https://gofile.io" });
  } catch (e) { if (process.env.DEBUG_LINKS) console.log("[extractor catch]", String(e)); }
  return out;
}

// ------------------------------------------------------------------- Filesdl

export async function resolveFilesdl(rawUrl: string): Promise<StreamLink[]> {
  const out: StreamLink[] = [];
  try {
    const { ok, $ } = await getDoc(rawUrl, { "User-Agent": DESKTOP_UA }, 45000);
    if (!ok) return out;
    const titleText = squash($("div.title").text() || "");
    const q = qNum(titleText);
    for (const el of $("div.container a[href]").toArray()) {
      const source = squash($(el).text() || "");
      const href = clean($(el).attr("href") || "");
      if (!href.startsWith("http")) continue;
      if (/hubcloud/i.test(source)) {
        out.push(...await extractHubCloudPage(href, "Filesdl", "Hub-Cloud"));
      } else if (/gdfl?ix/i.test(source)) {
        out.push(...await resolveGdflix(href, "Filesdl"));
      } else if (/gofile/i.test(source)) {
        out.push(...await resolveGofile(href));
      } else if (/direct download|ultra fastdl|fast cloud-02/i.test(source)) {
        const loc = await resolveRedirectLocation(href, rawUrl);
        out.push(link("filesdl", `Filesdl [Direct]`, loc || href, q, rawUrl));
      } else if (/fast cloud/i.test(source)) {
        out.push(link("filesdl", `Filesdl [Fast Cloud]`, href, q, rawUrl));
      }
    }
  } catch (e) { if (process.env.DEBUG_LINKS) console.log("[extractor catch]", String(e)); }
  return out;
}

// ------------------------------------------------------------------ M4ulinks

export async function resolveM4ulinks(rawUrl: string, referer: string): Promise<StreamLink[]> {
  const out: StreamLink[] = [];
  try {
    const { ok, text, finalUrl } = await getRaw(rawUrl, { "User-Agent": DESKTOP_UA, Referer: referer }, 45000);
    if (!ok) return out;
    const html = text.replace(/&amp;/g, "&");
    const q = qNum(html);
    const direct = [...new Set(
      [...html.matchAll(/https?:\/\/[^"'<>\s]*(?:r2\.dev|busycdn\.xyz|multidownload\.lol|indexserver|\.mp4|\.mkv)\/?[^"'<>\s]*/gi)]
        .map((m) => clean(m[0]))
    )].slice(0, 3);
    for (const d of direct) out.push(link("m4ulinks", "M4uDirect", d, q, finalUrl));
  } catch (e) { if (process.env.DEBUG_LINKS) console.log("[extractor catch]", String(e)); }
  return out;
}

// -------------------------------------------------------------- entry router

/** Host-based dispatch, mirrors MlsbdProvider.dispatchHost(). */
export async function dispatchHost(link: string, referer: string): Promise<StreamLink[]> {
  const l = link.toLowerCase();
  try {
    if (l.includes("pixeldrain.com")) {
      return await resolvePixeldrain(link, 0);
    }
    if (l.includes("filepress") || l.includes("filebee")) {
      return await resolveFilePress(link, referer);
    }
    if (l.includes("multicloud") || l.includes("multidownload")) {
      return await resolveMulticloud(link, referer);
    }
    if (l.includes("gdxfiles")) {
      return await resolveGdxFiles(link, referer);
    }
    if (l.includes("gdflix")) {
      return await resolveGdflix(link, referer);
    }
    if (l.includes("hubcloud") || l.includes("hubdrive")) {
      return await extractHubCloudPage(link, referer, "Hub-Cloud");
    }
    if (l.includes("shikshakdaak")) {
      return await extractHubCloudPage(link, referer, "Shikshakdaak");
    }
    if (l.includes("gofile")) {
      return await resolveGofile(link);
    }
    if (l.includes("filesdl")) {
      return await resolveFilesdl(link);
    }
    if (l.includes("m4ulinks")) {
      return await resolveM4ulinks(link, referer);
    }
  } catch { /* skip host */ }
  return [];
}

/** True when a raw r2/multidownload/etc URL is already a direct media link. */
export function isDirectMediaUrl(u: string): boolean {
  return /r2\.dev|busycdn\.xyz|multidownload\.lol|pixeldrain\.com\/api|indexserver|fastcdn|\.mp4(\?|$)|\.mkv(\?|$)/i.test(u);
}

/** Social/site-chrome filter (MlsbdProvider.isHostLink). */
export function isHostLink(link: string): boolean {
  const l = link.toLowerCase();
  if (!l.startsWith("http")) return false;
  return ["savelinks.me", "mlsbd.co", "t.me", "telegram", "whatsapp", "facebook", "twitter", "javascript:", "login"]
    .every((x) => !l.includes(x));
}
