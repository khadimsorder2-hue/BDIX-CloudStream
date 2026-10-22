"use client";

// Admin panel — owner only (yitube4k@gmail.com Google login).
// Shows: total users, per-user browse/extract counts, link cache health,
// 12h worker (cron) status + force-run button.

import { useCallback, useEffect, useState } from "react";
import { Icon, P } from "./icons";
import { imgUrl } from "./store";

interface AdminUser {
  id: string;
  email: string | null;
  display_name: string | null;
  avatar_url: string | null;
  provider: string;
  extract_count: number;
  last_extract_at: string | null;
  created_at: string;
  browses: number;
  watches: number;
}

interface Stats {
  totals: {
    users: number;
    browses: number;
    watches: number;
    favourites: number;
    cacheTotal: number;
    cacheFresh: number;
  };
  worker: {
    lastRun: { ran_at: string; refreshed: number; purged: number; ok: boolean; note: string | null } | null;
    runs: { id: number; ran_at: string; refreshed: number; purged: number; ok: boolean }[];
    healthy: boolean;
  };
  users: AdminUser[];
}

interface ExtractedLink {
  id: number | string;
  source: string;
  title: string;
  detail_url: string;
  linkCount: number;
  preview: string | null;
  directCount: number;
  extracted_by: string | null;
  updated_at: string;
  expires_at: string;
  fresh: boolean;
}

function timeAgo(iso: string | null): string {
  if (!iso) return "—";
  const s = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

function StatCard({ label, value, sub, color }: { label: string; value: string | number; sub?: string; color: string }) {
  return (
    <div className="rounded-xl" style={{ background: "#161616", border: "1px solid #1C1C20", padding: "13px 14px", flex: 1, minWidth: "45%" }}>
      <p style={{ fontSize: 10.5, letterSpacing: 1.2, textTransform: "uppercase", color: "#9ba0a4", fontWeight: 700 }}>{label}</p>
      <p className="font-bold" style={{ fontSize: 24, color, marginTop: 2 }}>{value}</p>
      {sub && <p style={{ fontSize: 11, color: "#6c7076", marginTop: 1 }}>{sub}</p>}
    </div>
  );
}

export function AdminScreen({ onBack }: { onBack: () => void }) {
  const [stats, setStats] = useState<Stats | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState("");
  const [warming, setWarming] = useState(false);
  const [warmMsg, setWarmMsg] = useState("");
  const [extracted, setExtracted] = useState<ExtractedLink[] | null>(null);
  const [exErr, setExErr] = useState("");
  const [exSource, setExSource] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await fetch("/api/admin/stats");
      const j = await r.json();
      if (!r.ok) { setErr(j.error || `HTTP ${r.status}`); setStats(null); }
      else { setStats(j); setErr(""); }
    } catch (e) {
      setErr(String(e));
    }
    setLoading(false);
  }, []);

  const loadExtracted = useCallback(async (source = "") => {
    setExSource(source);
    try {
      const r = await fetch(`/api/admin/links?limit=120${source ? `&source=${encodeURIComponent(source)}` : ""}`);
      const j = await r.json();
      if (!r.ok) { setExErr(j.error || `HTTP ${r.status}`); setExtracted(null); }
      else { setExtracted(j.items || []); setExErr(""); }
    } catch (e) {
      setExErr(String(e)); setExtracted(null);
    }
  }, []);

  useEffect(() => { load(); loadExtracted(); }, [load, loadExtracted]);

  const runWorker = async () => {
    setWarming(true);
    setWarmMsg("Running the 12h worker now (extracting latest links)…");
    try {
      const r = await fetch("/api/admin/stats", { method: "POST" });
      const j = await r.json();
      setWarmMsg(j.ok ? `Worker finished — refreshed ${j.result?.refreshed ?? "?"}, purged ${j.result?.purged ?? "?"}` : `Worker failed: ${j.error || "unknown"}`);
      await load();
    } catch (e) {
      setWarmMsg("Worker failed: " + String(e));
    }
    setWarming(false);
  };

  return (
    <div style={{ paddingBottom: 95 }}>
      {/* header */}
      <div className="flex items-center px-[13px] pt-4 pb-2" style={{ gap: 10 }}>
        <button onClick={onBack} className="cs-btn" style={{ padding: 6, marginLeft: -6 }}>
          <Icon d={P.back} size={22} color="#e9eaee" />
        </button>
        <h1 className="font-bold" style={{ fontSize: 19 }}>Admin</h1>
        <button onClick={load} className="cs-btn" style={{ marginLeft: "auto", color: "#9ba0a4", fontSize: 12.5, border: "1px solid #2B2C30", borderRadius: 8, padding: "6px 12px" }}>
          Refresh
        </button>
      </div>

      {loading && (
        <div className="flex flex-col items-center" style={{ paddingTop: "22vh", gap: 12 }}>
          <div className="cs-spinner" />
          <span style={{ fontSize: 12.5, color: "#9ba0a4" }}>Loading admin data…</span>
        </div>
      )}

      {err && !loading && (
        <div className="mx-[13px] rounded-xl" style={{ background: "#2a1418", border: "1px solid #ea596e55", padding: 16, marginTop: 10 }}>
          <p style={{ color: "#ea596e", fontSize: 13.5, fontWeight: 700 }}>Admin data load failed</p>
          <p style={{ color: "#c9ccd1", fontSize: 12.5, marginTop: 6, lineHeight: 1.6 }}>{err}</p>
        </div>
      )}

      {stats && !loading && (
        <>
          {/* stat cards */}
          <div className="flex flex-wrap px-[11px] pt-2" style={{ gap: 8 }}>
            <StatCard label="Users" value={stats.totals.users} sub="total registered" color="#aab5ff" />
            <StatCard label="Browses" value={stats.totals.browses} sub="detail opens + searches" color="#48E484" />
            <StatCard label="Watches" value={stats.totals.watches} sub="playback history rows" color="#F5B93B" />
            <StatCard label="Cached links" value={stats.totals.cacheFresh} sub={`fresh of ${stats.totals.cacheTotal} total`} color="#2ad0ff" />
          </div>

          {/* extracted links — what the link store holds */}
          <p className="font-bold px-[15px]" style={{ fontSize: 12, letterSpacing: 1.4, color: "#9ba0a4", textTransform: "uppercase", margin: "16px 0 8px" }}>
            Extracted links — Supabase link store
          </p>
          <div className="px-[11px] flex flex-wrap" style={{ gap: 6, marginBottom: 8 }}>
            {["", "mlsbd", "southfreak", "movielinkbd"].map((s) => (
              <button key={s || "all"} onClick={() => loadExtracted(s)} className="cs-btn font-bold"
                style={{
                  padding: "6px 12px", borderRadius: 16, fontSize: 11.5,
                  background: exSource === s ? "#3d50fa" : "#161616",
                  color: exSource === s ? "#fff" : "#9ba0a4",
                  border: "1px solid " + (exSource === s ? "#3d50fa" : "#1C1C20"),
                }}>
                {s || "All"}
              </button>
            ))}
          </div>
          {exErr && (
            <div className="mx-[13px] rounded-xl" style={{ background: "#2a1418", border: "1px solid #ea596e55", padding: 12 }}>
              <p style={{ color: "#ea596e", fontSize: 12.5 }}>Link store: {exErr}</p>
            </div>
          )}
          {extracted && extracted.length === 0 && !exErr && (
            <div className="mx-[13px] rounded-xl" style={{ background: "#161616", border: "1px solid #1C1C20", padding: 14 }}>
              <p style={{ color: "#9ba0a4", fontSize: 12.5, lineHeight: 1.6 }}>
                Link store ekhono khali — kono user ba 12h worker link extract korle ekhane dekhabe.
              </p>
            </div>
          )}
          {extracted && extracted.map((l) => (
            <div key={l.id} className="mx-[13px] rounded-xl" style={{ background: "#161616", border: "1px solid #1C1C20", padding: "11px 12px", marginBottom: 7 }}>
              <div className="flex items-center" style={{ gap: 8 }}>
                <span className="rounded-md font-bold" style={{ fontSize: 9.5, background: "#26272b", color: "#aab5ff", padding: "3px 7px", textTransform: "uppercase", letterSpacing: 0.6, flexShrink: 0 }}>
                  {l.source}
                </span>
                <p className="font-bold" style={{ fontSize: 12.5, flex: 1, minWidth: 0, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                  {l.title || l.detail_url}
                </p>
                <span style={{ fontSize: 10, color: l.fresh ? "#48E484" : "#ea596e", flexShrink: 0, fontWeight: 700 }}>
                  {l.fresh ? "FRESH" : "EXPIRED"}
                </span>
              </div>
              <div className="flex" style={{ gap: 6, marginTop: 7, flexWrap: "wrap" }}>
                <span style={{ fontSize: 10.5, background: "#26272b", borderRadius: 6, padding: "3px 8px", color: "#2ad0ff", fontWeight: 700 }}>{l.linkCount} links</span>
                <span style={{ fontSize: 10.5, background: "#26272b", borderRadius: 6, padding: "3px 8px", color: "#48E484" }}>{l.directCount} direct</span>
                <span style={{ fontSize: 10.5, background: "#26272b", borderRadius: 6, padding: "3px 8px", color: "#9ba0a4" }}>{timeAgo(l.updated_at)}</span>
                {l.preview && <span style={{ fontSize: 10, color: "#6c7076", width: "100%", wordBreak: "break-all", lineHeight: 1.5 }}>{l.preview}</span>}
              </div>
            </div>
          ))}

          {/* worker status */}
          <div className="mx-[13px] rounded-xl" style={{ background: "#161616", border: "1px solid #1C1C20", padding: "14px 15px", marginTop: 10 }}>
            <div className="flex items-center" style={{ gap: 8 }}>
              <span style={{ width: 9, height: 9, borderRadius: 9, background: stats.worker.healthy ? "#48E484" : "#ea596e", flexShrink: 0 }} />
              <p className="font-bold" style={{ fontSize: 14 }}>12h Worker</p>
              <span style={{ fontSize: 11.5, color: stats.worker.healthy ? "#48E484" : "#ea596e", marginLeft: "auto", fontWeight: 700 }}>
                {stats.worker.healthy ? "HEALTHY" : "STALE"}
              </span>
            </div>
            <p style={{ fontSize: 12, color: "#9ba0a4", marginTop: 7, lineHeight: 1.6 }}>
              {stats.worker.lastRun
                ? `Last run ${timeAgo(stats.worker.lastRun.ran_at)} — refreshed ${stats.worker.lastRun.refreshed} links, purged ${stats.worker.lastRun.purged} expired`
                : "Worker ekhono kono run koreni — cron (2x/day) ba 'Run now' chapun"}
            </p>
            <div className="flex items-center" style={{ gap: 8, marginTop: 11 }}>
              <button
                onClick={runWorker}
                disabled={warming}
                className="cs-btn font-bold"
                style={{ background: "#3d50fa", color: "#fff", fontSize: 12.5, padding: "8px 15px", borderRadius: 9, opacity: warming ? 0.6 : 1 }}
              >
                {warming ? "Running…" : "Run now"}
              </button>
              {warmMsg && <p style={{ fontSize: 11.5, color: "#9ba0a4", flex: 1, lineHeight: 1.5 }}>{warmMsg}</p>}
            </div>
          </div>

          {/* users table */}
          <p className="font-bold px-[15px]" style={{ fontSize: 12, letterSpacing: 1.4, color: "#9ba0a4", textTransform: "uppercase", margin: "16px 0 8px" }}>
            Users — browse / extract activity
          </p>
          <div className="px-[11px]">
            {stats.users.map((u) => (
              <div key={u.id} className="rounded-xl" style={{ background: "#161616", border: "1px solid #1C1C20", padding: "11px 12px", marginBottom: 7 }}>
                <div className="flex items-center" style={{ gap: 10 }}>
                  {u.avatar_url ? (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img src={imgUrl(u.avatar_url)} alt="" className="rounded-full" style={{ width: 34, height: 34, objectFit: "cover", flexShrink: 0 }} />
                  ) : (
                    <div className="rounded-full flex items-center justify-center font-bold" style={{ width: 34, height: 34, background: "#3d50fa", color: "#fff", fontSize: 14, flexShrink: 0 }}>
                      {(u.display_name || u.email || "U").slice(0, 1).toUpperCase()}
                    </div>
                  )}
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <p className="font-bold" style={{ fontSize: 13, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                      {u.display_name || "User"}
                      {u.email?.toLowerCase() === "yitube4k@gmail.com" && (
                        <span className="rounded-full font-bold" style={{ fontSize: 9, background: "rgba(61,80,250,0.2)", color: "#aab5ff", padding: "2px 7px", marginLeft: 7, letterSpacing: 0.6 }}>ADMIN</span>
                      )}
                    </p>
                    <p style={{ fontSize: 11, color: "#9ba0a4", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                      {u.email} · {u.provider} · joined {timeAgo(u.created_at)}
                    </p>
                  </div>
                </div>
                <div className="flex" style={{ gap: 6, marginTop: 9, flexWrap: "wrap" }}>
                  <span style={{ fontSize: 11, background: "#26272b", borderRadius: 7, padding: "4px 9px", color: "#48E484", fontWeight: 700 }}>browse {u.browses}</span>
                  <span style={{ fontSize: 11, background: "#26272b", borderRadius: 7, padding: "4px 9px", color: "#F5B93B", fontWeight: 700 }}>watch {u.watches}</span>
                  <span style={{ fontSize: 11, background: "#26272b", borderRadius: 7, padding: "4px 9px", color: "#2ad0ff", fontWeight: 700 }}>extract {u.extract_count}</span>
                  {u.last_extract_at && <span style={{ fontSize: 11, background: "#26272b", borderRadius: 7, padding: "4px 9px", color: "#9ba0a4" }}>last {timeAgo(u.last_extract_at)}</span>}
                </div>
              </div>
            ))}
          </div>

          {/* recent runs */}
          {stats.worker.runs.length > 0 && (
            <>
              <p className="font-bold px-[15px]" style={{ fontSize: 12, letterSpacing: 1.4, color: "#9ba0a4", textTransform: "uppercase", margin: "16px 0 8px" }}>
                Recent worker runs
              </p>
              <div className="px-[11px]">
                {stats.worker.runs.map((r) => (
                  <div key={r.id} className="flex items-center rounded-xl" style={{ background: "#161616", border: "1px solid #1C1C20", padding: "10px 12px", marginBottom: 6, gap: 10 }}>
                    <span style={{ width: 7, height: 7, borderRadius: 7, background: r.ok ? "#48E484" : "#ea596e", flexShrink: 0 }} />
                    <p style={{ fontSize: 12.5, color: "#c9ccd1", flex: 1 }}>
                      {timeAgo(r.ran_at)} — <b style={{ color: "#48E484" }}>+{r.refreshed}</b> / <b style={{ color: "#ea596e" }}>-{r.purged}</b>
                    </p>
                  </div>
                ))}
              </div>
            </>
          )}
        </>
      )}
    </div>
  );
}
