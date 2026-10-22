import { NextRequest, NextResponse } from "next/server";
import { requireUser } from "@/lib/api-auth";
import { getServiceClient, isAdminUser } from "@/lib/supabase/admin";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/**
 * GET /api/admin/links — owner-only.
 * Lists what the link store actually holds: which movie/episode pages had
 * stream links extracted (by the 12h worker or by any user) and cached
 * into the PUBLIC Supabase link_cache.
 * Query: ?source=<id>&limit=100
 */
export async function GET(req: NextRequest) {
  const gate = await requireUser(req);
  if ("error" in gate) return gate.error;

  const uid = gate.user.id;
  const email = gate.user.email || "";
  if (!(await isAdminUser(uid, email))) {
    return NextResponse.json({ error: "admin only" }, { status: 403 });
  }

  const svc = getServiceClient();
  if (!svc) return NextResponse.json({ error: "not-configured" }, { status: 503 });

  try {
    const sp = req.nextUrl.searchParams;
    const source = sp.get("source") || "";
    const limit = Math.min(Number(sp.get("limit") || 100), 300);

    let q = svc
      .from("link_cache")
      .select("id, source, title, detail_url, links, extracted_by, updated_at, expires_at")
      .order("updated_at", { ascending: false })
      .limit(limit);
    if (source) q = q.eq("source", source);

    const { data: rows, error } = await q;
    if (error) return NextResponse.json({ error: error.message }, { status: 500 });

    const now = new Date().toISOString();
    const items = (rows || []).map((r: Record<string, unknown>) => {
      const links = Array.isArray(r.links) ? (r.links as { url?: string; label?: string }[]) : [];
      return {
        id: r.id,
        source: r.source,
        title: r.title,
        detail_url: r.detail_url,
        linkCount: links.length,
        // first playable url preview (direct streams only)
        preview: links.find((l) => l.url)?.url?.slice(0, 90) || null,
        directCount: links.filter((l) => !l.url?.includes("go2.php")).length,
        extracted_by: r.extracted_by,
        updated_at: r.updated_at,
        expires_at: r.expires_at,
        fresh: (r.expires_at as string) > now,
      };
    });

    return NextResponse.json({ items, count: items.length });
  } catch (e) {
    return NextResponse.json({ error: String(e) }, { status: 500 });
  }
}
