import { NextRequest, NextResponse } from "next/server";
import { requireUser } from "@/lib/api-auth";
import { getSupabaseRoute } from "@/lib/supabase/server";
import { supabaseConfigured } from "@/lib/supabase/config";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// GET /api/favorites — list the signed-in user's favourites
export async function GET(req: NextRequest) {
  const gate = await requireUser(req);
  if ("error" in gate) return gate.error;
  const sb = getSupabaseRoute(req);
  if (!sb || !supabaseConfigured()) return NextResponse.json({ items: [] });
  const { data } = await sb
    .from("favorites")
    .select("*")
    .eq("user_id", gate.user.id)
    .order("created_at", { ascending: false })
    .limit(200);
  return NextResponse.json({ items: data || [] });
}

// POST /api/favorites — toggle { source, title, poster, url } -> { saved: bool }
export async function POST(req: NextRequest) {
  const gate = await requireUser(req);
  if ("error" in gate) return gate.error;
  const sb = getSupabaseRoute(req);
  if (!sb || !supabaseConfigured()) return NextResponse.json({ saved: false, reason: "not-configured" });
  try {
    const b = await req.json();
    const uid = gate.user.id;
    const detailUrl = String(b.url || "");
    if (!detailUrl || !b.source) return NextResponse.json({ saved: false }, { status: 400 });

    const { data: existing } = await sb
      .from("favorites")
      .select("id")
      .eq("user_id", uid)
      .eq("detail_url", detailUrl)
      .maybeSingle();

    if (existing) {
      await sb.from("favorites").delete().eq("id", (existing as { id: number }).id).eq("user_id", uid);
      return NextResponse.json({ saved: false });
    }
    const { error } = await sb.from("favorites").insert({
      user_id: uid,
      source: String(b.source),
      title: String(b.title || "Untitled"),
      poster: b.poster || null,
      detail_url: detailUrl,
    });
    if (error) return NextResponse.json({ saved: false, error: error.message }, { status: 400 });
    return NextResponse.json({ saved: true });
  } catch (e) {
    return NextResponse.json({ saved: false, error: String(e) }, { status: 400 });
  }
}
