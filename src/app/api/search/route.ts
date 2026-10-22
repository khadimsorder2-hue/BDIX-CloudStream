import { NextRequest, NextResponse } from "next/server";
import { SOURCES, sourceById } from "@/lib/providers";
import { cached } from "@/lib/fetcher";
import type { SearchItem } from "@/lib/providers/types";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(req: NextRequest) {
  const sp = req.nextUrl.searchParams;
  const source = sp.get("source") ?? "all";
  const q = (sp.get("q") ?? "").trim();
  const page = Number(sp.get("page") ?? "1") || 1;
  if (!q) return NextResponse.json({ groups: [] });

  try {
    if (source === "all") {
      const groups = await Promise.all(SOURCES.map(async (def) => {
        const items = await cached(`search:${def.id}:${q}:${page}`, 5 * 60 * 1000,
          () => def.search(q, page).catch(() => [] as SearchItem[]));
        return { source: def.id, name: def.name, color: def.color, items };
      }));
      return NextResponse.json({ groups: groups.filter((g) => g.items.length > 0) });
    }
    const def = sourceById(source);
    if (!def) return NextResponse.json({ error: "unknown source" }, { status: 400 });
    const items = await cached(`search:${def.id}:${q}:${page}`, 5 * 60 * 1000, () => def.search(q, page));
    return NextResponse.json({ groups: [{ source: def.id, name: def.name, color: def.color, items }] });
  } catch (e) {
    return NextResponse.json({ groups: [], error: String(e) });
  }
}
