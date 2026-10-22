import { NextRequest, NextResponse } from "next/server";
import { requireUser } from "@/lib/api-auth";
import { getServiceClient, isAdminUser } from "@/lib/supabase/admin";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/**
 * GET /api/admin/stats — owner-only.
 * Returns: total users, per-user browse/watch/extract counts,
 * link cache health, last 12h cron (worker) runs.
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
    // users
    const { data: profiles } = await svc
      .from("profiles")
      .select("id, email, display_name, avatar_url, provider, extract_count, last_extract_at, created_at")
      .order("created_at", { ascending: false })
      .limit(500);

    // per-user activity counts
    const { count: browseTotal } = await svc.from("browse_history").select("id", { count: "exact", head: true });
    const { count: watchTotal } = await svc.from("watch_history").select("id", { count: "exact", head: true });
    const { count: favTotal } = await svc.from("favorites").select("id", { count: "exact", head: true });

    const { data: browseRows } = await svc.from("browse_history").select("user_id").limit(5000);
    const { data: watchRows } = await svc.from("watch_history").select("user_id").limit(5000);
    const browseBy = new Map<string, number>();
    (browseRows || []).forEach((r: { user_id: string }) => browseBy.set(r.user_id, (browseBy.get(r.user_id) || 0) + 1));
    const watchBy = new Map<string, number>();
    (watchRows || []).forEach((r: { user_id: string }) => watchBy.set(r.user_id, (watchBy.get(r.user_id) || 0) + 1));

    const users = (profiles || []).map((p: Record<string, unknown>) => ({
      ...p,
      browses: browseBy.get(p.id as string) || 0,
      watches: watchBy.get(p.id as string) || 0,
    }));

    // link cache health
    const { count: cacheTotal } = await svc.from("link_cache").select("id", { count: "exact", head: true });
    const { count: cacheFresh } = await svc
      .from("link_cache")
      .select("id", { count: "exact", head: true })
      .gt("expires_at", new Date().toISOString());

    // worker (12h cron) runs
    const { data: runs } = await svc
      .from("worker_runs")
      .select("id, ran_at, kind, refreshed, purged, ok, note")
      .order("ran_at", { ascending: false })
      .limit(10);

    const lastRun = (runs || [])[0] || null;
    const now = Date.now();
    const lastOk = lastRun ? new Date(lastRun.ran_at as string).getTime() : 0;
    return NextResponse.json({
      totals: {
        users: (profiles || []).length,
        browses: browseTotal || 0,
        watches: watchTotal || 0,
        favourites: favTotal || 0,
        cacheTotal: cacheTotal || 0,
        cacheFresh: cacheFresh || 0,
      },
      worker: {
        lastRun,
        runs: runs || [],
        healthy: lastOk > now - 26 * 3600 * 1000, // 2x/day expected (12h)
      },
      users,
    });
  } catch (e) {
    return NextResponse.json({ error: String(e) }, { status: 500 });
  }
}

/** POST /api/admin/stats — force a warm (cron refresh) right now, owner only */
export async function POST(req: NextRequest) {
  const gate = await requireUser(req);
  if ("error" in gate) return gate.error;
  if (!(await isAdminUser(gate.user.id, gate.user.email || ""))) {
    return NextResponse.json({ error: "admin only" }, { status: 403 });
  }
  const cronSecret = process.env.CRON_SECRET || "skstream-cron-2026-secret";
  const origin = new URL(req.url).origin;
  try {
    const r = await fetch(`${origin}/api/warm`, {
      headers: { "x-cron-secret": cronSecret },
      signal: AbortSignal.timeout(280000),
    });
    const j = await r.json();
    return NextResponse.json({ ok: r.ok, result: j });
  } catch (e) {
    return NextResponse.json({ ok: false, error: String(e) });
  }
}
