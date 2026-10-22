// Signed stream URLs — hotlink protection ("link gulo onno kothao play hobe na").
// /api/links mints short-lived signed proxy URLs; /api/proxy verifies them.
// Raw upstream URLs are never handed to the client as playable links.

import crypto from "node:crypto";
import { proxySecret } from "@/lib/supabase/config";

const DEFAULT_TTL_SECONDS = 6 * 60 * 60; // 6h — links refresh on next open

function b64url(buf: Buffer): string {
  return buf.toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function hmac(payload: string, secret: string): string {
  return b64url(crypto.createHmac("sha256", secret).update(payload).digest());
}

/** signed proxy URL for an upstream stream url (optionally with referer) */
export function signProxyUrl(rawUrl: string, referer?: string, ttlSeconds = DEFAULT_TTL_SECONDS): string {
  const e = Math.floor(Date.now() / 1000) + ttlSeconds;
  const sig = hmac(`${rawUrl}|${e}`, proxySecret());
  let u = `/api/proxy?u=${encodeURIComponent(rawUrl)}&e=${e}&s=${sig}`;
  if (referer) u += `&referer=${encodeURIComponent(referer)}`;
  return u;
}

export interface TokenCheck {
  ok: boolean;
  reason?: string;
}

/** verify ?e= & ?s= for a raw upstream url */
export function verifyProxyToken(rawUrl: string, e: string | null, s: string | null): TokenCheck {
  if (!e || !s) return { ok: false, reason: "missing token" };
  const exp = Number(e);
  if (!Number.isFinite(exp)) return { ok: false, reason: "bad expiry" };
  if (exp * 1000 < Date.now()) return { ok: false, reason: "token expired" };
  const expect = hmac(`${rawUrl}|${exp}`, proxySecret());
  // timing-safe-ish compare (same length check + constant-time compare)
  if (s.length !== expect.length) return { ok: false, reason: "bad signature" };
  if (!crypto.timingSafeEqual(Buffer.from(s), Buffer.from(expect))) {
    return { ok: false, reason: "bad signature" };
  }
  return { ok: true };
}

/** reject cross-site playback: video requests must originate from our site */
export function sameSiteRequest(req: Request, host: string): boolean {
  const origin = req.headers.get("origin");
  const referer = req.headers.get("referer");
  const ok = (v: string) => {
    try {
      return new URL(v).host === host;
    } catch {
      return false;
    }
  };
  if (origin) return ok(origin);
  if (referer) return ok(referer);
  return true; // some native/Range fetches omit both — token still guards
}
