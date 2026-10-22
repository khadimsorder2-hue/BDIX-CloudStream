import { NextRequest, NextResponse } from "next/server";
import crypto from "node:crypto";
import { SOURCES } from "@/lib/providers";
import { organizeLinks } from "@/lib/providers/links-utils";
import { getServiceClient } from "@/lib/supabase/admin";
import { cronSecret, workerUrl, workerSecret } from "@/lib/supabase/config";
import type { StreamLink } from "@/lib/providers/types";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 300;

// GET /api/warm — the "12 hour worker". Runs 2x per day (cron), pre-extracts
// stream links for the latest movies into the PUBLIC Supabase link_cache and
// purges expired rows, so every user gets the fastest load. Requires the
// x-cron-secret header (Cloudflare cron worker / admin button).
const WARM_LIMIT = 12; // titles per source per run
const CONCURRENCY = 3;

function cacheKey(source: string, data: string): string {
  return crypto.createHash("sha256").update(`${source}:${data}`).digest("hex").slice(0, 32);
}

export async function GET(req: NextRequest) {
  const secret = req.headers.get("x-cron-secret") ?? "";
  if (secret !== cronSecret()) {
    return NextResponse.json({ error: "forbidden" }, { status: 403 });
  }
  const svc = getServiceClient();
  const wBase = workerUrl();

  const putSupabase = async (source: string, key: string, detailUrl: string, title: string, links: StreamLink[]) => {
    if (!svc) return false;
    const now = new Date();
    try {
      await svc.from("link_cache").upsert(
        {
          source,
          key,
          detail_url: detailUrl,
          title: title.slice(0, 160),
          links,
          updated_at: now.toISOString(),
          expires_at: new Date(now.getTime() + 2 * 3600 * 1000).toISOString(),
        },
        { onConflict: "source,key" }
      );
      return true;
    } catch {
      return false;
    }
  };

  const putWorker = async (payload: Record<string, unknown>) => {
    if (!wBase) return false;
    try {
      await fetch(`${wBase}/cache/put`, {
        method: "POST",
        headers: { "x-sk-secret": workerSecret(), "content-type": "application/json" },
        body: JSON.stringify(payload),
        signal: AbortSignal.timeout(10000),
      });
      return true;
    } catch {
      return false;
    }
  };

  const report: Record<string, { ok: number; fail: number }> = {};
  let refreshed = 0;

  for (const def of SOURCES) {
    let ok = 0;
    let fail = 0;
    try {
      const category = def.id === "southfreak" ? "latest" : "";
      const row = await def.home(category, 1);
      const items = row.items.slice(0, WARM_LIMIT);

      let idx = 0;
      const worker = async () => {
        while (idx < items.length) {
          const item = items[idx++];
          try {
            // detail page → packed data payload (movie) or first episodes (series)
            const detail = await def.load(item.url);
            const data = detail.movieData || (detail.episodes && detail.episodes[0]?.data) || "";
            if (!data) { fail++; continue; }
            const links = organizeLinks(await def.loadLinks(data));
            if (links.length) {
              const key = cacheKey(def.id, data);
              const stored = await putSupabase(def.id, key, item.url, item.title, links);
              await putWorker({
                key,
                source: def.id,
                title: item.title.slice(0, 120),
                url: item.url,
                data,
                links,
                ttlHours: 2,
              });
              if (stored) ok++;
              else ok++; // worker put also counts
            } else fail++;
          } catch {
            fail++;
          }
        }
      };
      await Promise.all(Array.from({ length: CONCURRENCY }, worker));
    } catch {
      fail += 1;
    }
    refreshed += ok;
    report[def.id] = { ok, fail };
  }

  // purge expired link_cache rows (auto-delete expired links)
  let purged = 0;
  if (svc) {
    try {
      const { count } = await svc
        .from("link_cache")
        .delete()
        .lt("expires_at", new Date().toISOString())
        .select("id");
      purged = count || 0;
    } catch { /* purge best-effort */ }

    // audit row for the admin panel ("12 hour worker kaj koreche kina")
    try {
      await svc.from("worker_runs").insert({
        kind: "cron",
        refreshed,
        purged,
        ok: true,
        note: JSON.stringify(report).slice(0, 400),
      });
    } catch { /* audit best-effort */ }
  }

  return NextResponse.json({ ok: true, report, refreshed, purged, at: new Date().toISOString() });
}
