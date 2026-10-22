// Shared HTTP helpers for the provider ports (Kotlin `app.get` equivalent).
// Desktop Chrome UA — both mlsbd.co and southfreak.vip serve plain 200s to it.

export const DESKTOP_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

export const DOC_HEADERS: Record<string, string> = {
  "User-Agent": DESKTOP_UA,
  Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
  "Accept-Language": "bn,en-US;q=0.9,en;q=0.8",
};

// ---------------------------------------------------------- in-memory cache
type CacheEntry = { at: number; value: unknown };
const mem = new Map<string, CacheEntry>();
const MEM_MAX = 500;

export async function cached<T>(key: string, ttlMs: number, fn: () => Promise<T>): Promise<T> {
  const hit = mem.get(key);
  const now = Date.now();
  if (hit && now - hit.at < ttlMs) return hit.value as T;
  const value = await fn();
  // never cache empty-looking results (a flaky upstream should retry)
  const empty =
    value == null ||
    (Array.isArray(value) && value.length === 0) ||
    (typeof value === "object" && "items" in (value as object) &&
      Array.isArray((value as { items: unknown[] }).items) &&
      (value as { items: unknown[] }).items.length === 0);
  if (!empty) {
    if (mem.size > MEM_MAX) mem.clear();
    mem.set(key, { at: now, value });
  }
  return value;
}

// ------------------------------------------------------------------- fetch

export async function getDoc(url: string, headers: Record<string, string> = DOC_HEADERS, timeoutMs = 30000) {
  const res = await getRaw(url, headers, timeoutMs);
  return { ...res, $: loadHtml(res.text) };
}

export async function getJson<T>(url: string, headers: Record<string, string> = DOC_HEADERS, timeoutMs = 30000): Promise<T | null> {
  try {
    const res = await getRaw(url, headers, timeoutMs);
    return JSON.parse(res.text) as T;
  } catch {
    return null;
  }
}

export async function getRaw(url: string, headers: Record<string, string> = DOC_HEADERS, timeoutMs = 30000) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { headers, signal: ctrl.signal, redirect: "follow" });
    const text = await res.text();
    if (res.ok || (res.status !== 403 && res.status !== 429 && res.status !== 503)) {
      return { ok: res.ok, status: res.status, text, finalUrl: res.url };
    }
    // fall through: Cloudflare TLS-fingerprint blocks hit undici (403/429/503)
  } catch {
    // fall through to curl
  } finally {
    clearTimeout(t);
  }
  return curlFallback(url, headers, timeoutMs);
}

/**
 * Some hosts (mlsbd.co) accept curl's TLS fingerprint but block Node's
 * undici JA3 with a 403 challenge page. Shell out to curl as a fallback.
 * (Node-only — the Cloudflare worker bundle skips this path entirely.)
 */
async function curlFallback(url: string, headers: Record<string, string>, timeoutMs: number) {
  const isNode = typeof process !== "undefined" && !!(process as { versions?: { node?: string } }).versions?.node;
  if (!isNode) return { ok: false, status: 0, text: "", finalUrl: url };
  try {
    const { execFile } = await import("child_process");
    const args = [
      "-s", "-L", "--max-time", String(Math.ceil(timeoutMs / 1000)),
      "-w", "\n__CURL_STATUS__%{http_code}\t%{url_effective}",
    ];
    for (const [k, v] of Object.entries(headers)) args.push("-H", `${k}: ${v}`);
    args.push(url);
    const out = await new Promise<string>((resolve, reject) => {
      execFile("curl", args, { maxBuffer: 20 * 1024 * 1024 }, (err, stdout) =>
        err ? reject(err) : resolve(stdout));
    });
    const m = /__CURL_STATUS__(\d+)\t(\S+)\s*$/.exec(out);
    const status = m ? Number(m[1]) : 0;
    const finalUrl = m?.[2] ?? url;
    const text = out.replace(/\n__CURL_STATUS__\d+\t\S+\s*$/, "");
    return { ok: status >= 200 && status < 400, status, text, finalUrl };
  } catch {
    return { ok: false, status: 0, text: "", finalUrl: url };
  }
}

export async function getText(url: string, headers: Record<string, string> = DOC_HEADERS, timeoutMs = 30000) {
  try {
    return (await getRaw(url, headers, timeoutMs)).text;
  } catch {
    return null;
  }
}

/** POST with the same 403/429/503 curl fallback as getRaw (form-encoded body). */
export async function postRaw(
  url: string,
  body: Record<string, string> | string,
  headers: Record<string, string> = DOC_HEADERS,
  timeoutMs = 30000
) {
  const bodyStr = typeof body === "string" ? body : new URLSearchParams(body).toString();
  const hdrs = { ...headers, "Content-Type": "application/x-www-form-urlencoded" };
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { method: "POST", headers: hdrs, body: bodyStr, signal: ctrl.signal, redirect: "follow" });
    const text = await res.text();
    if (res.ok || (res.status !== 403 && res.status !== 429 && res.status !== 503)) {
      return { ok: res.ok, status: res.status, text, finalUrl: res.url };
    }
  } catch {
    // fall through to curl
  } finally {
    clearTimeout(t);
  }
  return curlPostFallback(url, bodyStr, hdrs, timeoutMs);
}

async function curlPostFallback(url: string, bodyStr: string, headers: Record<string, string>, timeoutMs: number) {
  const isNode = typeof process !== "undefined" && !!(process as { versions?: { node?: string } }).versions?.node;
  if (!isNode) return { ok: false, status: 0, text: "", finalUrl: url };
  try {
    const { execFile } = await import("child_process");
    const args = [
      "-s", "-L", "--max-time", String(Math.ceil(timeoutMs / 1000)),
      "-w", "\n__CURL_STATUS__%{http_code}\t%{url_effective}",
      "-X", "POST", "--data", bodyStr,
    ];
    for (const [k, v] of Object.entries(headers)) args.push("-H", `${k}: ${v}`);
    args.push(url);
    const out = await new Promise<string>((resolve, reject) => {
      execFile("curl", args, { maxBuffer: 20 * 1024 * 1024 }, (err, stdout) =>
        err ? reject(err) : resolve(stdout));
    });
    const m = /__CURL_STATUS__(\d+)\t(\S+)\s*$/.exec(out);
    const status = m ? Number(m[1]) : 0;
    const finalUrl = m?.[2] ?? url;
    const text = out.replace(/\n__CURL_STATUS__\d+\t\S+\s*$/, "");
    return { ok: status >= 200 && status < 400, status, text, finalUrl };
  } catch {
    return { ok: false, status: 0, text: "", finalUrl: url };
  }
}

// ------------------------------------------------------------------ cheerio
import * as cheerio from "cheerio";

export type Cheerp = ReturnType<typeof cheerio.load>;

export function loadHtml(html: string): Cheerp {
  return cheerio.load(html);
}

/** absolute url from possibly-relative href */
export function absUrl($: Cheerp, el: cheerio.Element | null, base: string): string {
  if (!el) return "";
  const href = $(el).attr("href") ?? "";
  try {
    return new URL(href, base).toString();
  } catch {
    return href;
  }
}

export function decodeHtmlEntities(s: string): string {
  return s
    .replace(/&#038;|&amp;/g, "&")
    .replace(/&#8211;/g, "-")
    .replace(/&#8217;/g, "'")
    .replace(/&#8220;/g, '"')
    .replace(/&#8221;/g, '"')
    .replace(/&quot;/g, '"')
    .replace(/&nbsp;/g, " ")
    .replace(/&#(\d+);/g, (_, d) => String.fromCharCode(Number(d)));
}

export function squash(s: string): string {
  return s.replace(/\s+/g, " ").trim();
}

/** "Download in 1080p (Source) - 6.97GB" / "4K" -> "1080p" style label */
export function qualityLabel(text: string): string | null {
  const m = /\b(2160|1440|1080|720|576|480|360|240)p?\b|\b4k\b/i.exec(text);
  if (!m) return null;
  const token = m[0].toLowerCase();
  if (token === "4k") return "2160p";
  return token.endsWith("p") ? token : `${token}p`;
}

export function qualityInt(text: string): number {
  const q = qualityLabel(text);
  return q ? Number(q.replace("p", "")) || 0 : 0;
}
