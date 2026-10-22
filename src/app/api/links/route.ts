import { NextRequest, NextResponse } from "next/server";
import crypto from "node:crypto";
import { sourceById } from "@/lib/providers";
import { organizeLinks } from "@/lib/providers/links-utils";
import { signProxyUrl } from "@/lib/stream-sign";
import { requireUser } from "@/lib/api-auth";
import { getServiceClient } from "@/lib/supabase/admin";
import { workerUrl, workerSecret } from "@/lib/supabase/config";
import type { StreamLink } from "@/lib/providers/types";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 120;

// link resolution touches several upstream pages; cache short-lived
const cache = new Map<string, { at: number; links: StreamLink[] }>();
const TTL = 5 * 60 * 1000;

function cacheKey(source: string, data: string): string {
  return crypto.createHash("sha256").update(`${source}:${data}`).digest("hex").slice(0, 32);
}

/**
 * Range-probe a link: signed CDN urls die before the cache TTL does, so a
 * cached row is only served if its first playable link still answers a
 * 1-byte Range request. 5s timeout — dead/blocked → caller falls through
 * to live extraction instead of feeding the player a corpse.
 */
async function probeAlive(url: string): Promise<boolean> {
  try {
    const r = await fetch(url, {
      headers: { Range: "bytes=0-1" },
      redirect: "follow",
      signal: AbortSignal.timeout(5000),
    });
    return r.status === 200 || r.status === 206;
  } catch {
    return false;
  }
}

async function firstPlayableAlive(links: StreamLink[]): Promise<boolean> {
  const target = links.find((l) => !l.isPage);
  if (!target) return false; // page-only cache rows are useless for playback
  return probeAlive(target.url);
}

/** wrap playable links with signed proxy urls (raw urls stay for pages) */
function wrapLinks(links: StreamLink[]): (StreamLink & { playUrl?: string })[] {
  return links.map((l) =>
    l.isPage ? l : { ...l, playUrl: signProxyUrl(l.url, l.referer || undefined) }
  );
}

/**
 * GET /api/links — auth required.
 * Order: memory → Supabase PUBLIC link_cache (shared by all users, fastest
 * load) → worker KV (optional) → live extraction → store back to Supabase
 * (any user's extraction becomes public for everyone) + extract counter.
 */
export async function GET(req: NextRequest) {
  const gate = await requireUser(req);
  if ("error" in gate) return gate.error;

  const sp = req.nextUrl.searchParams;
  const source = sp.get("source") ?? "";
  const data = sp.get("data") ?? "";
  const title = sp.get("title") ?? "";
  const refresh = sp.get("refresh") === "1";
  const def = sourceById(source);
  if (!def || !data) return NextResponse.json({ error: "bad request", links: [] }, { status: 400 });

  const key = `${source}:${data}`;
  const sKey = cacheKey(source, data);
  const svc = getServiceClient();
  const wBase = workerUrl();
  const wHeaders: Record<string, string> = { "x-sk-secret": workerSecret() };

  // refresh=1 → full cache bypass (player auto-refresh after all sources
  // failed). Empty live extraction falls back to the old cache below.
  if (!refresh) {
    const hit = cache.get(key);
    if (hit && Date.now() - hit.at < TTL) {
      if (await firstPlayableAlive(hit.links)) {
        return NextResponse.json({ links: wrapLinks(hit.links), cached: "memory" });
      }
      cache.delete(key); // dead links — re-extract below
    }

    // 1) Supabase PUBLIC link cache — fresh rows only, probe-gated
    if (svc) {
      try {
        const { data: rows } = await svc
          .from("link_cache")
          .select("links, expires_at")
          .eq("source", source)
          .eq("key", sKey)
          .gt("expires_at", new Date().toISOString())
          .maybeSingle();
        const links = (rows as { links?: StreamLink[] } | null)?.links;
        if (Array.isArray(links) && links.length && (await firstPlayableAlive(links))) {
          cache.set(key, { at: Date.now(), links });
          return NextResponse.json({ links: wrapLinks(links), cached: "supabase" });
        }
      } catch { /* cache is best-effort */ }
    }

    // 2) Cloudflare worker KV cache (optional, fast + shared) — probe-gated
    if (wBase) {
      try {
        const r = await fetch(`${wBase}/cache/links?key=${encodeURIComponent(sKey)}`, {
          headers: wHeaders,
          signal: AbortSignal.timeout(8000),
        });
        if (r.ok) {
          const j = (await r.json()) as { links?: StreamLink[] };
          if (Array.isArray(j.links) && j.links.length && (await firstPlayableAlive(j.links))) {
            cache.set(key, { at: Date.now(), links: j.links });
            return NextResponse.json({ links: wrapLinks(j.links), cached: "worker" });
          }
        }
      } catch { /* cache is best-effort */ }
    }
  }

  // 3) live extraction
  try {
    const links = organizeLinks(await def.loadLinks(data));
    if (links.length) {
      cache.set(key, { at: Date.now(), links });
    } else if (refresh) {
      // refresh produced nothing — serve the previous cache if it exists
      const prev = cache.get(key) || null;
      if (prev) {
        return NextResponse.json({ links: wrapLinks(prev.links), cached: "stale-fallback" });
      }
    }

    if (links.length) {
      // 4) store PUBLIC in Supabase — "jei user ei jei movie search korey
      //    stream link jeita extract hobi oitao sobar jonno store rakhbe"
      //    signed CDN links die well before 10h — 2h keeps the cache honest
      if (svc) {
        const now = new Date();
        const expires = new Date(now.getTime() + 2 * 3600 * 1000);
        try {
          await svc.from("link_cache").upsert(
            {
              source,
              key: sKey,
              detail_url: data,
              title: (title || data).slice(0, 160),
              links,
              extracted_by: gate.user.id === "setup-mode" ? null : gate.user.id,
              updated_at: now.toISOString(),
              expires_at: expires.toISOString(),
            },
            { onConflict: "source,key" }
          );
          // count the user's extraction (admin panel info)
          if (gate.user.id !== "setup-mode") {
            await svc.rpc("increment_extracts", { uid: gate.user.id });
          }
        } catch { /* store best-effort */ }
      }

      // 5) worker KV writeback (optional)
      if (wBase) {
        fetch(`${wBase}/cache/put`, {
          method: "POST",
          headers: { ...wHeaders, "content-type": "application/json" },
          body: JSON.stringify({
            key: sKey,
            source,
            title: data.slice(0, 120),
            url: data,
            links,
            ttlHours: 2,
          }),
          signal: AbortSignal.timeout(8000),
        }).catch(() => {});
      }
    }

    return NextResponse.json({ links: wrapLinks(links), cached: refresh ? "live-refresh" : "live" });
  } catch (e) {
    return NextResponse.json({ links: [], error: String(e) });
  }
}
