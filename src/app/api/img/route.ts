import { NextRequest } from "next/server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// Poster image proxy — avoids hotlink blocks + mixed-content issues.
const DESKTOP_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

export async function GET(req: NextRequest) {
  const u = req.nextUrl.searchParams.get("u");
  if (!u || !u.startsWith("http")) return new Response("bad url", { status: 400 });

  try {
    const upstream = await fetch(u, {
      headers: { "User-Agent": DESKTOP_UA, Accept: "image/*,*/*" },
      signal: AbortSignal.timeout(20000),
    });
    if (!upstream.ok) return new Response("upstream " + upstream.status, { status: 502 });
    const ct = upstream.headers.get("content-type") ?? "image/jpeg";
    if (!ct.startsWith("image/")) return new Response("not an image", { status: 415 });
    const buf = await upstream.arrayBuffer();
    return new Response(buf, {
      headers: {
        "Content-Type": ct,
        "Cache-Control": "public, max-age=86400",
        "Access-Control-Allow-Origin": "*",
      },
    });
  } catch (e) {
    return new Response(String(e), { status: 502 });
  }
}
