import { NextRequest, NextResponse } from "next/server";
import { proxySecret } from "@/lib/supabase/config";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// GET /api/sign?u=<url> — mint a short-lived signed proxy URL for an
// arbitrary upstream asset the player needs (subtitles). Session-gated via
// the same auth used by links/detail (player runs only inside the app).
const crypto = await import("node:crypto");

function b64url(buf: Buffer): string {
  return buf.toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function GET(req: NextRequest) {
  const sp = req.nextUrl.searchParams;
  const u = sp.get("u");
  const ttl = 60 * 60; // 1h for subs
  if (!u || !u.startsWith("http")) return NextResponse.json({ error: "bad url" }, { status: 400 });
  // auth: require a supabase session cookie when configured
  const hasSession = req.cookies.getAll().some((c) => /-auth-token/.test(c.name));
  if (!hasSession && process.env.NEXT_PUBLIC_SUPABASE_URL) {
    return NextResponse.json({ error: "auth required" }, { status: 401 });
  }
  const e = Math.floor(Date.now() / 1000) + ttl;
  const sig = b64url(crypto.createHmac("sha256", proxySecret()).update(`${u}|${e}`).digest());
  return NextResponse.json({
    url: `/api/proxy?u=${encodeURIComponent(u)}&e=${e}&s=${sig}`,
  });
}
