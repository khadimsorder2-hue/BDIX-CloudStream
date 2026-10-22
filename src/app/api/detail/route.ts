import { NextRequest, NextResponse } from "next/server";
import { sourceById } from "@/lib/providers";
import { cached } from "@/lib/fetcher";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 60;

export async function GET(req: NextRequest) {
  const sp = req.nextUrl.searchParams;
  const source = sp.get("source") ?? "";
  const url = sp.get("url") ?? "";
  const def = sourceById(source);
  if (!def || !url.startsWith("http")) {
    return NextResponse.json({ error: "bad request" }, { status: 400 });
  }

  try {
    const detail = await cached(`detail:${source}:${url}`, 30 * 60 * 1000, () => def.load(url));
    return NextResponse.json(detail);
  } catch (e) {
    return NextResponse.json({ error: String(e) }, { status: 502 });
  }
}
