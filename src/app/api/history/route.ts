import { NextRequest, NextResponse } from "next/server";
import { requireUser } from "@/lib/api-auth";
import { getSupabaseRoute } from "@/lib/supabase/server";
import { supabaseConfigured } from "@/lib/supabase/config";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// POST /api/history  — record watch / browse / search activity
// body: { kind: 'watch'|'browse'|'search', source, title, poster, url,
//         query?, episodeIndex?, episodeName?, position?, duration?, finished? }
export async function POST(req: NextRequest) {
  const gate = await requireUser(req);
  if ("error" in gate) return gate.error;
  const sb = getSupabaseRoute(req);
  if (!sb || !supabaseConfigured()) return NextResponse.json({ ok: false, reason: "not-configured" });

  try {
    const b = await req.json();
    const uid = gate.user.id;

    if (b.kind === "watch") {
      const row = {
        user_id: uid,
        source: String(b.source || ""),
        title: String(b.title || "Untitled"),
        poster: b.poster || null,
        detail_url: String(b.url || ""),
        episode_index: Number(b.episodeIndex || 0),
        episode_name: b.episodeName || null,
        position_seconds: Number(b.position || 0),
        duration_seconds: Number(b.duration || 0),
        finished: !!b.finished,
        updated_at: new Date().toISOString(),
      };
      if (!row.detail_url || !row.source) return NextResponse.json({ ok: false }, { status: 400 });
      const { error } = await sb
        .from("watch_history")
        .upsert(row, { onConflict: "user_id,detail_url" });
      if (error) return NextResponse.json({ ok: false, error: error.message }, { status: 400 });
      return NextResponse.json({ ok: true });
    }

    if (b.kind === "browse" || b.kind === "search") {
      const row = {
        user_id: uid,
        kind: b.kind,
        source: b.source || null,
        title: b.title || null,
        poster: b.poster || null,
        url: b.url || null,
        query: b.query || null,
      };
      if (b.kind === "search" && !row.query) return NextResponse.json({ ok: false }, { status: 400 });
      if (b.kind === "browse" && !row.url) return NextResponse.json({ ok: false }, { status: 400 });
      const { error } = await sb.from("browse_history").insert(row);
      if (error) return NextResponse.json({ ok: false, error: error.message }, { status: 400 });
      return NextResponse.json({ ok: true });
    }

    return NextResponse.json({ ok: false, error: "bad kind" }, { status: 400 });
  } catch (e) {
    return NextResponse.json({ ok: false, error: String(e) }, { status: 400 });
  }
}

// GET /api/history?kind=watch|browse|search&limit=50
export async function GET(req: NextRequest) {
  const gate = await requireUser(req);
  if ("error" in gate) return gate.error;
  const sb = getSupabaseRoute(req);
  if (!sb || !supabaseConfigured()) return NextResponse.json({ items: [] });

  const sp = req.nextUrl.searchParams;
  const kind = sp.get("kind") || "watch";
  const limit = Math.min(Number(sp.get("limit") || 50), 200);
  const uid = gate.user.id;

  if (kind === "watch") {
    const { data } = await sb
      .from("watch_history")
      .select("*")
      .eq("user_id", uid)
      .order("updated_at", { ascending: false })
      .limit(limit);
    return NextResponse.json({ items: data || [] });
  }
  // browse/search: keep only the most recent entry per url/query
  const { data } = await sb
    .from("browse_history")
    .select("*")
    .eq("user_id", uid)
    .eq("kind", kind === "search" ? "search" : "detail")
    .order("created_at", { ascending: false })
    .limit(limit);
  const seen = new Set<string>();
  interface BrowseRow { url?: string | null; query?: string | null }
  const items = (data || []).filter((r: BrowseRow) => {
    const key = (r.url || r.query || "").trim();
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  return NextResponse.json({ items });
}

// DELETE /api/history?kind=watch|browse|search  — clear history
export async function DELETE(req: NextRequest) {
  const gate = await requireUser(req);
  if ("error" in gate) return gate.error;
  const sb = getSupabaseRoute(req);
  if (!sb || !supabaseConfigured()) return NextResponse.json({ ok: false });
  const kind = req.nextUrl.searchParams.get("kind") || "watch";
  const uid = gate.user.id;
  if (kind === "watch") await sb.from("watch_history").delete().eq("user_id", uid);
  else await sb.from("browse_history").delete().eq("user_id", uid).eq("kind", kind === "search" ? "search" : "detail");
  return NextResponse.json({ ok: true });
}
