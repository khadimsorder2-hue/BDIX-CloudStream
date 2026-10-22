import { NextRequest, NextResponse } from "next/server";
import { sourceById } from "@/lib/providers";
import { cached } from "@/lib/fetcher";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(req: NextRequest) {
  const sp = req.nextUrl.searchParams;
  const source = sp.get("source") ?? "mlsbd";
  const category = sp.get("category") ?? "";
  const page = Number(sp.get("page") ?? "1") || 1;

  const def = sourceById(source);
  if (!def) return NextResponse.json({ error: "unknown source" }, { status: 400 });

  try {
    const row = await cached(`home:${source}:${category}:${page}`, 10 * 60 * 1000,
      () => def.home(category, page));
    return NextResponse.json({ ...row, category, page, source: def.id });
  } catch (e) {
    return NextResponse.json({ error: String(e), items: [], hasNext: false }, { status: 200 });
  }
}
