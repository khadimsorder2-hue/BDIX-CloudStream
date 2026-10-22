// SkStream — Cloudflare Pages advanced-mode worker.
// Serves /api/* (Next.js API routes ported 1:1) + static assets fallback.
// Includes: supabase auth callback, history/favourites (per-user),
// signed stream proxy (hotlink protection), link-cache writeback hooks.
// Bundle: esbuild --bundle --format=esm -> out/_worker.js

import { SOURCES, sourceById } from "../lib/providers";
import { cached, DESKTOP_UA } from "../lib/fetcher";
import { organizeLinks } from "../lib/providers/links-utils";
import type { StreamLink } from "../lib/providers/types";
import { createServerClient } from "@supabase/ssr";

interface Env {
  ASSETS: { fetch: (req: Request) => Promise<Response> };
  SUPABASE_URL?: string;
  SUPABASE_ANON_KEY?: string;
  SUPABASE_SERVICE_KEY?: string;
  PROXY_SECRET?: string;
  CRON_SECRET?: string;
  ADMIN_EMAIL?: string;
}

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json",
      "access-control-allow-origin": "*",
      "cache-control": "no-store",
    },
  });

// short-lived link cache (isolate-scoped, same as the Next dev routes)
const linkCache = new Map<string, { at: number; links: StreamLink[] }>();
const LINK_TTL = 5 * 60 * 1000;

// ------------------------------------------------------------ link_cache REST

async function sha256hex(s: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", enc.encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 32);
}

/** fresh public cache row (expires_at > now) or null */
async function cacheGet(env: Env, source: string, key: string): Promise<StreamLink[] | null> {
  if (!env.SUPABASE_URL || !env.SUPABASE_ANON_KEY) return null;
  try {
    const u = `${env.SUPABASE_URL}/rest/v1/link_cache?select=links&source=eq.${encodeURIComponent(source)}&key=eq.${key}&expires_at=gt.${new Date().toISOString()}&limit=1`;
    const r = await fetch(u, { headers: { apikey: env.SUPABASE_ANON_KEY, Authorization: `Bearer ${env.SUPABASE_ANON_KEY}` } });
    if (!r.ok) return null;
    const rows = (await r.json()) as { links?: StreamLink[] }[];
    return rows?.[0]?.links ?? null;
  } catch {
    return null;
  }
}

/** store PUBLIC (service_role bypasses RLS) */
async function cachePut(env: Env, source: string, key: string, detailUrl: string, title: string, links: StreamLink[], extractedBy: string | null): Promise<boolean> {
  if (!env.SUPABASE_URL || !env.SUPABASE_SERVICE_KEY) return false;
  try {
    const now = new Date();
    const row = {
      source, key, detail_url: detailUrl, title: title.slice(0, 160), links,
      extracted_by: extractedBy,
      updated_at: now.toISOString(),
      expires_at: new Date(now.getTime() + 2 * 3600 * 1000).toISOString(),
    };
    const r = await fetch(`${env.SUPABASE_URL}/rest/v1/link_cache?on_conflict=source,key`, {
      method: "POST",
      headers: {
        apikey: env.SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${env.SUPABASE_SERVICE_KEY}`,
        "Content-Type": "application/json",
        Prefer: "resolution=merge-duplicates,return=minimal",
      },
      body: JSON.stringify(row),
    });
    return r.ok;
  } catch {
    return false;
  }
}

async function incrementExtracts(env: Env, uid: string): Promise<void> {
  if (!env.SUPABASE_URL || !env.SUPABASE_SERVICE_KEY) return;
  try {
    await fetch(`${env.SUPABASE_URL}/rest/v1/rpc/increment_extracts`, {
      method: "POST",
      headers: {
        apikey: env.SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${env.SUPABASE_SERVICE_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ uid }),
    });
  } catch { /* best-effort */ }
}

// ------------------------------------------------------------ supabase glue

function parseCookies(req: Request): { name: string; value: string }[] {
  const h = req.headers.get("cookie") || "";
  const out: { name: string; value: string }[] = [];
  for (const part of h.split(/;\s*/)) {
    const i = part.indexOf("=");
    if (i > 0) out.push({ name: part.slice(0, i).trim(), value: part.slice(i + 1).trim() });
  }
  return out;
}

function serializeCookie(name: string, value: string, options: Record<string, unknown> | undefined): string {
  const o = options || {};
  const parts = [`${name}=${value}`, `Path=${(o.path as string) ?? "/"}`];
  if (o.maxAge != null) parts.push(`Max-Age=${o.maxAge}`);
  if (o.domain) parts.push(`Domain=${o.domain}`);
  parts.push(`SameSite=${o.sameSite === "lax" ? "Lax" : o.sameSite === "strict" ? "Strict" : "None"}`);
  if (o.secure !== false) parts.push("Secure");
  if (o.httpOnly !== false) parts.push("HttpOnly");
  return parts.join("; ");
}

function sbClient(req: Request, env: Env, sink?: string[]) {
  return createServerClient(env.SUPABASE_URL || "", env.SUPABASE_ANON_KEY || "", {
    cookies: {
      getAll() {
        return parseCookies(req);
      },
      setAll(cookies) {
        if (!sink) return;
        for (const c of cookies) sink.push(serializeCookie(c.name, c.value, c.options));
      },
    },
  });
}

const sbConfigured = (env: Env) => !!(env.SUPABASE_URL && env.SUPABASE_ANON_KEY);

/** session-gate: returns the user or null (rule: account chara stream nai) */
async function gateUser(req: Request, env: Env) {
  if (!sbConfigured(env)) return { id: "setup-mode", email: "" }; // setup not finished
  try {
    const { data } = await sbClient(req, env).auth.getUser();
    return data?.user ?? null;
  } catch {
    return null;
  }
}

// --------------------------------------------------------- stream signing

const enc = new TextEncoder();

function b64url(buf: ArrayBuffer): string {
  let s = "";
  const bytes = new Uint8Array(buf);
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function hmac(payload: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
  );
  return b64url(await crypto.subtle.sign("HMAC", key, enc.encode(payload)));
}

async function signProxyUrl(rawUrl: string, secret: string, referer?: string, ttl = 6 * 3600): Promise<string> {
  const e = Math.floor(Date.now() / 1000) + ttl;
  const sig = await hmac(`${rawUrl}|${e}`, secret);
  let u = `/api/proxy?u=${encodeURIComponent(rawUrl)}&e=${e}&s=${sig}`;
  if (referer) u += `&referer=${encodeURIComponent(referer)}`;
  return u;
}

async function verifyProxyToken(rawUrl: string, e: string | null, s: string | null, secret: string): Promise<boolean> {
  if (!e || !s) return false;
  const exp = Number(e);
  if (!Number.isFinite(exp)) return false;
  if (exp * 1000 < Date.now()) return false;
  const expect = await hmac(`${rawUrl}|${exp}`, secret);
  if (s.length !== expect.length) return false;
  let diff = 0;
  for (let i = 0; i < s.length; i++) diff |= s.charCodeAt(i) ^ expect.charCodeAt(i);
  return diff === 0;
}

/** same-site check: video must originate from our site when header present */
function sameSiteRequest(req: Request): boolean {
  const host = new URL(req.url).host;
  const ok = (v: string) => {
    try { return new URL(v).host === host; } catch { return false; }
  };
  const origin = req.headers.get("origin");
  if (origin) return ok(origin);
  const referer = req.headers.get("referer");
  if (referer) return ok(referer);
  return true; // some Range fetches omit both — signed token still guards
}

/** wrap playable links with signed proxy urls (page links stay raw) */
async function wrapLinks(links: StreamLink[], secret: string) {
  return Promise.all(
    links.map(async (l) =>
      l.isPage ? l : { ...l, playUrl: await signProxyUrl(l.url, secret, l.referer || undefined) }
    )
  );
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const sp = url.searchParams;
    const p = url.pathname;
    const proxySecret = env.PROXY_SECRET || "skstream-dev-proxy-secret";

    // --------------------------------------------------- supabase oauth cb
    if (p === "/api/auth/callback") {
      if (!sbConfigured(env)) return Response.redirect(new URL("/", req.url).toString(), 302);
      const code = sp.get("code");
      const setCookies: string[] = [];
      const sb = sbClient(req, env, setCookies);
      if (code) {
        try { await sb.auth.exchangeCodeForSession(code); } catch { /* fallthrough to home */ }
      }
      const headers = new Headers();
      headers.set("Location", new URL("/", req.url).toString());
      for (const c of setCookies) headers.append("Set-Cookie", c);
      return new Response(null, { status: 302, headers });
    }

    // ------------------------------------------------------------- history
    if (p === "/api/history") {
      if (!sbConfigured(env)) return json({ ok: false, reason: "not-configured" });
      const user = await gateUser(req, env);
      if (!user) return json({ error: "auth required" }, 401);
      const sb = sbClient(req, env);
      try {
        if (req.method === "POST") {
          const b = (await req.json()) as Record<string, unknown>;
          const kind = String(b.kind || "watch");
          if (kind === "watch") {
            const row = {
              user_id: user.id,
              source: String(b.source || ""),
              title: String(b.title || "Untitled"),
              poster: (b.poster as string) || null,
              detail_url: String(b.url || ""),
              episode_index: Number(b.episodeIndex || 0),
              episode_name: (b.episodeName as string) || null,
              position_seconds: Number(b.position || 0),
              duration_seconds: Number(b.duration || 0),
              finished: !!b.finished,
              updated_at: new Date().toISOString(),
            };
            const { error } = await sb.from("watch_history")
              .upsert(row, { onConflict: "user_id,detail_url" });
            if (error) return json({ ok: false, error: error.message }, 500);
            return json({ ok: true });
          }
          // browse / search
          const row: Record<string, unknown> = {
            user_id: user.id,
            kind: kind === "search" ? "search" : "detail",
            source: (b.source as string) || null,
            title: (b.title as string) || null,
            poster: (b.poster as string) || null,
            url: (b.url as string) || null,
            query: (b.query as string) || null,
          };
          const { error } = await sb.from("browse_history").insert(row);
          if (error) return json({ ok: false, error: error.message }, 500);
          return json({ ok: true });
        }
        if (req.method === "GET") {
          const kind = sp.get("kind") || "watch";
          if (kind === "watch") {
            const { data } = await sb.from("watch_history")
              .select("*").eq("user_id", user.id)
              .order("updated_at", { ascending: false }).limit(60);
            return json({ items: data ?? [] });
          }
          const { data } = await sb.from("browse_history")
            .select("*").eq("user_id", user.id)
            .order("created_at", { ascending: false }).limit(120);
          return json({ items: data ?? [] });
        }
        if (req.method === "DELETE") {
          const kind = sp.get("kind") || "watch";
          if (kind === "watch") {
            await sb.from("watch_history").delete().eq("user_id", user.id);
          } else {
            await sb.from("browse_history").delete().eq("user_id", user.id);
          }
          return json({ ok: true });
        }
        return json({ error: "method" }, 405);
      } catch (e) {
        return json({ error: String(e) }, 500);
      }
    }

    // ----------------------------------------------------------- favourites
    if (p === "/api/favorites") {
      if (!sbConfigured(env)) return json({ ok: false, reason: "not-configured" });
      const user = await gateUser(req, env);
      if (!user) return json({ error: "auth required" }, 401);
      const sb = sbClient(req, env);
      try {
        if (req.method === "GET") {
          const { data } = await sb.from("favorites")
            .select("*").eq("user_id", user.id)
            .order("created_at", { ascending: false }).limit(200);
          return json({ items: data ?? [] });
        }
        if (req.method === "POST") {
          const b = (await req.json()) as Record<string, unknown>;
          const u = String(b.url || "");
          if (!u) return json({ ok: false, error: "url required" }, 400);
          const { data: ex } = await sb.from("favorites")
            .select("id").eq("user_id", user.id).eq("detail_url", u).maybeSingle();
          if (ex) {
            await sb.from("favorites").delete().eq("id", (ex as { id: number }).id);
            return json({ ok: true, faved: false });
          }
          const { error } = await sb.from("favorites").insert({
            user_id: user.id,
            source: String(b.source || ""),
            title: String(b.title || "Untitled"),
            poster: (b.poster as string) || null,
            detail_url: u,
          });
          if (error) return json({ ok: false, error: error.message }, 500);
          return json({ ok: true, faved: true });
        }
        return json({ error: "method" }, 405);
      } catch (e) {
        return json({ error: String(e) }, 500);
      }
    }

    // ---------------------------------------------------------------- sign
    if (p === "/api/sign") {
      const u = sp.get("u");
      if (!u || !u.startsWith("http")) return json({ error: "bad url" }, 400);
      const user = await gateUser(req, env);
      if (!user) return json({ error: "auth required" }, 401);
      return json({ url: await signProxyUrl(u, proxySecret, undefined, 3600) });
    }

    // ------------------------------------------------------------- home
    if (p === "/api/home") {
      const title = sp.get("title") || "";
    const source = sp.get("source") ?? "mlsbd";
      const category = sp.get("category") ?? "";
      const page = Number(sp.get("page") ?? "1") || 1;
      const def = sourceById(source);
      if (!def) return json({ error: "unknown source" }, 400);
      try {
        const row = await cached(`home:${source}:${category}:${page}`, 10 * 60 * 1000,
          () => def.home(category, page));
        return json({ ...row, category, page, source: def.id });
      } catch (e) {
        return json({ error: String(e), items: [], hasNext: false });
      }
    }

    // ----------------------------------------------------------- search
    if (p === "/api/search") {
      const source = sp.get("source") ?? "all";
      const q = (sp.get("q") ?? "").trim();
      const page = Number(sp.get("page") ?? "1") || 1;
      if (!q) return json({ groups: [] });
      try {
        if (source === "all") {
          const groups = await Promise.all(SOURCES.map(async (def) => {
            const items = await cached(`search:${def.id}:${q}:${page}`, 5 * 60 * 1000,
              () => def.search(q, page).catch(() => []));
            return { source: def.id, name: def.name, color: def.color, items };
          }));
          return json({ groups: groups.filter((g) => g.items.length > 0) });
        }
        const def = sourceById(source);
        if (!def) return json({ error: "unknown source" }, 400);
        const items = await cached(`search:${def.id}:${q}:${page}`, 5 * 60 * 1000, () => def.search(q, page));
        return json({ groups: [{ source: def.id, name: def.name, color: def.color, items }] });
      } catch (e) {
        return json({ groups: [], error: String(e) });
      }
    }

    // ----------------------------------------------------------- detail
    if (p === "/api/detail") {
      const source = sp.get("source") ?? "";
      const target = sp.get("url") ?? "";
      const def = sourceById(source);
      if (!def || !target.startsWith("http")) return json({ error: "bad request" }, 400);
      try {
        const detail = await cached(`detail:${source}:${target}`, 30 * 60 * 1000, () => def.load(target));
        return json(detail);
      } catch (e) {
        return json({ error: String(e) }, 502);
      }
    }

    // ------------------------------------------------------------ links
    if (p === "/api/links") {
      const user = await gateUser(req, env);
      if (!user) return json({ error: "auth required", links: [] }, 401);
      const source = sp.get("source") ?? "";
      const data = sp.get("data") ?? "";
      const def = sourceById(source);
      if (!def || !data) return json({ error: "bad request", links: [] }, 400);
      const key = `${source}:${data}`;
      const hit = linkCache.get(key);
      if (hit && Date.now() - hit.at < LINK_TTL) {
        return json({ links: await wrapLinks(hit.links, proxySecret), cached: "memory" });
      }
      // PUBLIC supabase cache — any user's extraction is shared instantly
      const cachedLinks = await cacheGet(env, source, await sha256hex(`${source}:${data}`));
      if (cachedLinks && cachedLinks.length) {
        linkCache.set(key, { at: Date.now(), links: cachedLinks });
        return json({ links: await wrapLinks(cachedLinks, proxySecret), cached: "supabase" });
      }
      try {
        const links = organizeLinks(await def.loadLinks(data));
        linkCache.set(key, { at: Date.now(), links });
        if (links.length) {
          const uid = user.id === "setup-mode" ? null : user.id;
          await cachePut(env, source, await sha256hex(`${source}:${data}`), data, (title || data), links, uid);
          if (uid) await incrementExtracts(env, uid);
        }
        return json({ links: await wrapLinks(links, proxySecret), cached: "live" });
      } catch (e) {
        return json({ links: [], error: String(e) });
      }
    }

    // ------------------------------------------------------- 12h warm cron
    if (p === "/api/warm") {
      const secret = req.headers.get("x-cron-secret") ?? "";
      if (!env.CRON_SECRET || secret !== env.CRON_SECRET) return json({ error: "forbidden" }, 403);
      const WARM_LIMIT = 12;
      const report: Record<string, { ok: number; fail: number }> = {};
      let refreshed = 0;
      for (const def of SOURCES) {
        let ok = 0, fail = 0;
        try {
          const category = def.id === "southfreak" ? "latest" : "";
          const row = await def.home(category, 1);
          const items = row.items.slice(0, WARM_LIMIT);
          for (const item of items) {
            try {
              const detail = await def.load(item.url);
              const data = detail.movieData || (detail.episodes && detail.episodes[0]?.data) || "";
              if (!data) { fail++; continue; }
              const links = organizeLinks(await def.loadLinks(data));
              if (links.length) {
                await cachePut(env, def.id, await sha256hex(`${def.id}:${data}`), item.url, item.title, links, null);
                ok++;
              } else fail++;
            } catch { fail++; }
          }
        } catch { fail += 1; }
        refreshed += ok;
        report[def.id] = { ok, fail };
      }
      // purge expired + audit row (service key)
      let purged = 0;
      if (env.SUPABASE_URL && env.SUPABASE_SERVICE_KEY) {
        const H = { apikey: env.SUPABASE_SERVICE_KEY, Authorization: `Bearer ${env.SUPABASE_SERVICE_KEY}`, "Content-Type": "application/json", Prefer: "return=representation" };
        try {
          const del = await fetch(`${env.SUPABASE_URL}/rest/v1/link_cache?expires_at=lt.${new Date().toISOString()}&select=id`, { method: "DELETE", headers: H });
          if (del.ok) purged = ((await del.json()) as unknown[]).length;
        } catch { /* purge best-effort */ }
        try {
          await fetch(`${env.SUPABASE_URL}/rest/v1/worker_runs`, {
            method: "POST", headers: { ...H, Prefer: "return=minimal" },
            body: JSON.stringify({ kind: "cron", refreshed, purged, ok: true, note: JSON.stringify(report).slice(0, 400) }),
          });
        } catch { /* audit best-effort */ }
      }
      return json({ ok: true, report, refreshed, purged, at: new Date().toISOString() });
    }

    // ------------------------------------------------------- admin stats
    if (p === "/api/admin/stats") {
      const user = await gateUser(req, env);
      if (!user) return json({ error: "admin only" }, 403);
      const adminEmail = (env.ADMIN_EMAIL || "yitube4k@gmail.com").toLowerCase();
      if ((user.email || "").toLowerCase() !== adminEmail) return json({ error: "admin only" }, 403);
      if (!env.SUPABASE_URL || !env.SUPABASE_SERVICE_KEY) return json({ error: "not-configured" }, 503);
      const H = { apikey: env.SUPABASE_SERVICE_KEY, Authorization: `Bearer ${env.SUPABASE_SERVICE_KEY}` };
      const B = env.SUPABASE_URL + "/rest/v1/";
      try {
        const getJson = async (path: string) => {
          const r = await fetch(B + path, { headers: H });
          return r.ok ? r.json() : null;
        };
        const profiles = (await getJson("profiles?select=id,email,display_name,avatar_url,provider,extract_count,last_extract_at,created_at&order=created_at.desc&limit=500")) || [];
        const browseRows = (await getJson("browse_history?select=user_id&limit=5000")) || [];
        const watchRows = (await getJson("watch_history?select=user_id&limit=5000")) || [];
        const cacheRows = (await getJson("link_cache?select=id,expires_at&limit=5000")) || [];
        const runs = (await getJson("worker_runs?select=id,ran_at,kind,refreshed,purged,ok,note&order=ran_at.desc&limit=10")) || [];
        const countBy = (rows: { user_id: string }[]) => {
          const m = new Map<string, number>();
          rows.forEach((r) => m.set(r.user_id, (m.get(r.user_id) || 0) + 1));
          return m;
        };
        const bb = countBy(browseRows), wb = countBy(watchRows);
        const now = Date.now();
        const fresh = cacheRows.filter((c: { expires_at: string }) => new Date(c.expires_at).getTime() > now).length;
        const users = profiles.map((pf: Record<string, unknown>) => ({
          ...pf,
          browses: bb.get(pf.id as string) || 0,
          watches: wb.get(pf.id as string) || 0,
        }));
        const lastRun = runs[0] || null;
        return json({
          totals: {
            users: profiles.length,
            browses: browseRows.length,
            watches: watchRows.length,
            cacheTotal: cacheRows.length,
            cacheFresh: fresh,
          },
          worker: { lastRun, runs, healthy: lastRun ? now - new Date(lastRun.ran_at).getTime() < 26 * 3600 * 1000 : false },
          users,
        });
      } catch (e) {
        return json({ error: String(e) }, 500);
      }
    }

    // ------------------------------------------------- admin: extracted links
    if (p === "/api/admin/links") {
      const user = await gateUser(req, env);
      if (!user) return json({ error: "admin only" }, 403);
      const adminEmail2 = (env.ADMIN_EMAIL || "yitube4k@gmail.com").toLowerCase();
      if ((user.email || "").toLowerCase() !== adminEmail2) return json({ error: "admin only" }, 403);
      if (!env.SUPABASE_URL || !env.SUPABASE_SERVICE_KEY) return json({ error: "not-configured" }, 503);
      const H2 = { apikey: env.SUPABASE_SERVICE_KEY, Authorization: `Bearer ${env.SUPABASE_SERVICE_KEY}` };
      const B2 = env.SUPABASE_URL + "/rest/v1/";
      try {
        const srcFilter = sp.get("source") || "";
        const limit = Math.min(Number(sp.get("limit") || 120), 300);
        let path = `link_cache?select=id,source,title,detail_url,links,extracted_by,updated_at,expires_at&order=updated_at.desc&limit=${limit}`;
        if (srcFilter) path += `&source=eq.${encodeURIComponent(srcFilter)}`;
        const r = await fetch(B2 + path, { headers: H2 });
        const rows = r.ok ? (await r.json()) as Record<string, unknown>[] : [];
        const now = Date.now();
        const items = rows.map((rw) => {
          const links = Array.isArray(rw.links) ? (rw.links as { url?: string }[]) : [];
          return {
            id: rw.id,
            source: rw.source,
            title: rw.title,
            detail_url: rw.detail_url,
            linkCount: links.length,
            preview: links.find((l) => l.url)?.url?.slice(0, 90) || null,
            directCount: links.filter((l) => !(l.url || "").includes("go2.php")).length,
            extracted_by: rw.extracted_by,
            updated_at: rw.updated_at,
            expires_at: rw.expires_at,
            fresh: String(rw.expires_at) > new Date(now).toISOString(),
          };
        });
        return json({ items, count: items.length });
      } catch (e) {
        return json({ error: String(e) }, 500);
      }
    }

    // ------------------------------------------------------------ proxy
    if (p === "/api/proxy") {
      const u = sp.get("u");
      if (!u || !u.startsWith("http")) return new Response("bad url", { status: 400 });
      if (!(await verifyProxyToken(u, sp.get("e"), sp.get("s"), proxySecret))) {
        return new Response("bad or expired token", { status: 403 });
      }
      if (!sameSiteRequest(req)) {
        return new Response("cross-site playback blocked", { status: 403 });
      }
      const headers: Record<string, string> = {
        "User-Agent": DESKTOP_UA,
        Accept: "*/*",
        "Accept-Encoding": "identity",
      };
      const referer = sp.get("referer") ?? "";
      if (referer) headers["Referer"] = referer;
      const range = req.headers.get("range");
      if (range) headers["Range"] = range;
      let upstream: Response;
      try {
        upstream = await fetch(u, { headers, redirect: "follow" });
      } catch (e) {
        return new Response(`upstream error: ${e}`, { status: 502 });
      }
      const out = new Headers();
      for (const h of ["content-type", "content-length", "content-range", "accept-ranges"]) {
        const v = upstream.headers.get(h);
        if (v) out.set(h, v);
      }
      if (!out.has("content-type")) out.set("content-type", "video/mp4");
      if (/text\/html/i.test(out.get("content-type") ?? "")) {
        return new Response("upstream is a web page, not a media stream", { status: 415 });
      }
      out.set("Access-Control-Allow-Origin", "*");
      out.set("Cache-Control", "no-store");
      return new Response(upstream.body, { status: upstream.status, headers: out });
    }

    // -------------------------------------------------------------- img
    if (p === "/api/img") {
      const u = sp.get("u");
      if (!u || !u.startsWith("http")) return new Response("bad url", { status: 400 });
      try {
        const upstream = await fetch(u, {
          headers: { "User-Agent": DESKTOP_UA, Accept: "image/*,*/*" },
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

    // -------------------------------------------------- static assets
    return env.ASSETS.fetch(req);
  },
};
