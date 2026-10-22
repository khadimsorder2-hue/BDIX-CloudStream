import { NextRequest } from "next/server";
import { verifyProxyToken, sameSiteRequest, signProxyUrl } from "@/lib/stream-sign";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 300;

// Stream proxy: spoofs the desktop UA + referer for UA-locked BDIX CDNs
// (bcdn.hakunaymatata.com 428, busycdn, indexserver...), passes Range
// through so seeking works, and streams the body without buffering.
// HOTLINK PROTECTION: every request must carry a valid signed token
// (e=expiry, s=HMAC) minted by /api/links — tokens are unforgeable without
// PROXY_SECRET and expire, so extracted links only play here, logged in.

const DESKTOP_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

export async function GET(req: NextRequest) {
  const sp = req.nextUrl.searchParams;
  const u = sp.get("u");
  const referer = sp.get("referer") ?? "";
  if (!u || !u.startsWith("http")) {
    return new Response("bad url", { status: 400 });
  }

  // ---- hotlink protection -------------------------------------------------
  const check = verifyProxyToken(u, sp.get("e"), sp.get("s"));
  if (!check.ok) {
    return new Response(`forbidden: ${check.reason}`, { status: 403 });
  }
  if (!sameSiteRequest(req, req.headers.get("host") || "")) {
    return new Response("forbidden: cross-site playback", { status: 403 });
  }

  const headers: Record<string, string> = {
    "User-Agent": DESKTOP_UA,
    Accept: "*/*",
    "Accept-Encoding": "identity",
  };
  if (referer) headers["Referer"] = referer;
  const range = req.headers.get("range");
  if (range) headers["Range"] = range;

  const ctrl = new AbortController();
  let upstream: Response;
  try {
    upstream = await fetch(u, { headers, signal: ctrl.signal, redirect: "follow" });
  } catch (e) {
    return new Response(`upstream error: ${e}`, { status: 502 });
  }

  const passthrough = ["content-type", "content-length", "content-range", "accept-ranges"];
  const out = new Headers();
  for (const h of passthrough) {
    const v = upstream.headers.get(h);
    if (v) out.set(h, v);
  }
  if (!out.has("content-type")) out.set("content-type", "video/mp4");
  // host *pages* (expired tokens / landing pages) return text/html — a
  // <video> element can't demux HTML, so fail fast with 415 to trigger the
  // player's source failover instead of a slow confusing error.
  const ct = out.get("content-type") ?? "";
  if (/text\/html/i.test(ct)) {
    ctrl.abort();
    return new Response("upstream is a web page, not a media stream", { status: 415 });
  }
  out.set("Access-Control-Allow-Origin", "*");
  out.set("Cache-Control", "no-store");

  // ---- HLS playlists: rewrite every segment URI back through this proxy
  // so the whole stream stays behind signed tokens (no raw URLs leak).
  if (/mpegurl| x-mpegurl|vnd\.apple\.mpegurl/i.test(ct) || /\.m3u8(\?|$)/i.test(u)) {
    const text = await upstream.text();
    const base = new URL(u).href;
    const rewritten = text
      .split("\n")
      .map((line) => {
        const t = line.trim();
        if (!t || t.startsWith("#")) return line;
        try {
          const abs = new URL(t, base).href;
          return signProxyUrl(abs, referer || undefined);
        } catch {
          return line;
        }
      })
      .join("\n");
    out.set("content-type", "application/vnd.apple.mpegurl");
    out.delete("content-length");
    return new Response(rewritten, { status: upstream.status, headers: out });
  }

  return new Response(upstream.body, { status: upstream.status, headers: out });
}
