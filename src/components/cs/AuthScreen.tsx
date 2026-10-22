"use client";

// SkStream auth gate + account hub (Supabase).
// - AuthScreen: signup / login / Google login / password reset
//   (no account → nothing streams; the whole app sits behind this)
// - AccountScreen: profile + watch/browse history + favourites (Supabase)

import { useCallback, useEffect, useState } from "react";
import { Icon, P } from "./icons";
import { CsLogo } from "./HomeScreen";
import { PosterCard, PosterSkeleton } from "./PosterRow";
import { getSupabaseBrowser } from "@/lib/supabase/client";
import { supabaseConfigured } from "@/lib/supabase/config";
import {
  fetchRemoteFavorites, fetchRemoteHistory, clearRemoteHistory,
  imgUrl, fmtTime, sourceById,
  type SearchItem,
} from "./store";

// ---------------------------------------------------------------- session

export interface SessionInfo {
  userId: string;
  email: string;
  name: string;
  avatar: string | null;
  provider: string;
}

export function useSession() {
  const [session, setSession] = useState<SessionInfo | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const sb = getSupabaseBrowser();
    if (!sb) { setLoading(false); return; }
    let alive = true;
    (async () => {
      const { data } = await sb.auth.getSession();
      const s = data.session;
      if (alive) {
        setSession(s ? {
          userId: s.user.id,
          email: s.user.email || "",
          name: (s.user.user_metadata?.full_name || s.user.user_metadata?.name || s.user.email || "User").toString(),
          avatar: (s.user.user_metadata?.avatar_url as string) || null,
          provider: s.user.app_metadata?.provider || "email",
        } : null);
        setLoading(false);
      }
    })();
    const { data: sub } = sb.auth.onAuthStateChange((_e, s) => {
      setSession(s ? {
        userId: s.user.id,
        email: s.user.email || "",
        name: (s.user.user_metadata?.full_name || s.user.user_metadata?.name || s.user.email || "User").toString(),
        avatar: (s.user.user_metadata?.avatar_url as string) || null,
        provider: s.user.app_metadata?.provider || "email",
      } : null);
    });
    return () => { alive = false; sub.subscription.unsubscribe(); };
  }, []);

  return { session, loading };
}

// ------------------------------------------------------------ auth screen

type Mode = "login" | "signup" | "reset";

export function AuthScreen({ onAuthed }: { onAuthed?: () => void }) {
  const [mode, setMode] = useState<Mode>("login");
  const [email, setEmail] = useState("");
  const [pass, setPass] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  const sb = getSupabaseBrowser();

  // prefill email across modes
  useEffect(() => { setMsg(null); }, [mode]);

  if (!supabaseConfigured() || !sb) {
    return (
      <div className="cs-app flex flex-col items-center justify-center" style={{ minHeight: "100vh", padding: 30, gap: 14 }}>
        <CsLogo size={54} />
        <h1 className="font-bold" style={{ fontSize: 22, marginTop: 6 }}>SkStream</h1>
        <p style={{ color: "#9ba0a4", fontSize: 13.5, textAlign: "center", lineHeight: 1.7, maxWidth: 340 }}>
          Account system setup hocche — Supabase keys ekhono add kora hoyni.
          Thiki howar por ei screen-e account create / login / Google login dekhabe.
        </p>
        <div className="rounded-xl" style={{ background: "#161616", border: "1px solid #1C1C20", padding: "14px 18px", marginTop: 8, maxWidth: 380, width: "100%" }}>
          <p className="font-bold" style={{ fontSize: 12, letterSpacing: 1, color: "#9ba0a4", textTransform: "uppercase", marginBottom: 8 }}>Setup pending</p>
          <p style={{ fontSize: 12.5, color: "#c9ccd1", lineHeight: 1.7 }}>
            1. supabase.com/dashboard signup (email: yitube4k@gmail.com)<br />
            2. Auto-setup script project + keys niye ashbe<br />
            3. Dev server restart — app ready
          </p>
        </div>
      </div>
    );
  }

  const submit = async () => {
    if (!email.trim() || (mode !== "reset" && !pass)) return;
    setBusy(true); setMsg(null);
    try {
      if (mode === "login") {
        const { error } = await sb.auth.signInWithPassword({ email: email.trim(), password: pass });
        if (error) setMsg({ ok: false, text: error.message });
        else onAuthed?.();
      } else if (mode === "signup") {
        const { error } = await sb.auth.signUp({ email: email.trim(), password: pass });
        if (error) setMsg({ ok: false, text: error.message });
        else setMsg({ ok: true, text: "Account created! Email confirmation send hoyeche — inbox check koro (jodi confirm on thake)." });
      } else {
        const { error } = await sb.auth.resetPasswordForEmail(email.trim());
        if (error) setMsg({ ok: false, text: error.message });
        else setMsg({ ok: true, text: "Reset link pathano hoyeche — email check koro." });
      }
    } finally {
      setBusy(false);
    }
  };

  const google = async () => {
    setBusy(true); setMsg(null);
    try {
      const { error } = await sb.auth.signInWithOAuth({
        provider: "google",
        options: { redirectTo: window.location.origin + "/api/auth/callback" },
      });
      if (error) { setMsg({ ok: false, text: error.message }); setBusy(false); }
      // redirect happens on success
    } catch (e) {
      setMsg({ ok: false, text: String(e) });
      setBusy(false);
    }
  };

  const field = {
    background: "#303135", color: "#e9eaee", borderRadius: 10,
    border: "1px solid #2B2C30", fontSize: 14.5, padding: "13px 15px", width: "100%",
    outline: "none",
  } as React.CSSProperties;

  return (
    <div className="cs-app flex flex-col items-center" style={{ minHeight: "100vh", padding: "12vh 26px 40px", gap: 0 }}>
      <CsLogo size={50} />
      <h1 className="font-bold" style={{ fontSize: 24, marginTop: 12 }}>SkStream</h1>
      <p style={{ color: "#9ba0a4", fontSize: 13, marginTop: 4, marginBottom: 26 }}>
        {mode === "login" ? "Sign in to continue watching" : mode === "signup" ? "Create your account" : "Reset your password"}
      </p>

      <div className="flex flex-col" style={{ gap: 10, width: "100%", maxWidth: 380 }}>
        {mode !== "reset" && (
          <button
            onClick={google}
            disabled={busy}
            className="cs-btn flex items-center justify-center"
            style={{ background: "#fff", color: "#1C1C20", fontWeight: 700, fontSize: 14, padding: "12px 16px", borderRadius: 10, gap: 10 }}
          >
            <svg width="18" height="18" viewBox="0 0 24 24"><path fill="#4285F4" d="M23.5 12.3c0-.8-.1-1.6-.2-2.3H12v4.5h6.5c-.3 1.5-1.1 2.8-2.4 3.6v3h3.9c2.3-2.1 3.5-5.2 3.5-8.8z"/><path fill="#34A853" d="M12 24c3.2 0 6-1.1 7.9-2.9l-3.9-3c-1.1.7-2.5 1.2-4 1.2-3.1 0-5.7-2.1-6.7-4.9H1.3v3.1C3.3 21.3 7.3 24 12 24z"/><path fill="#FBBC05" d="M5.3 14.4c-.2-.7-.4-1.5-.4-2.4s.1-1.7.4-2.4V6.5H1.3C.5 8.2 0 10 0 12s.5 3.8 1.3 5.5l4-3.1z"/><path fill="#EA4335" d="M12 4.8c1.8 0 3.3.6 4.6 1.8L20 3.1C18 1.2 15.2 0 12 0 7.3 0 3.3 2.7 1.3 6.5l4 3.1c1-2.8 3.6-4.8 6.7-4.8z"/></svg>
            Continue with Google
          </button>
        )}
        {mode !== "reset" && (
          <div className="flex items-center" style={{ gap: 12, margin: "6px 0" }}>
            <div style={{ flex: 1, height: 1, background: "#2B2C30" }} />
            <span style={{ fontSize: 11.5, color: "#9ba0a4" }}>or</span>
            <div style={{ flex: 1, height: 1, background: "#2B2C30" }} />
          </div>
        )}

        <input
          type="email" value={email} onChange={(e) => setEmail(e.target.value)}
          placeholder="Email" style={field} autoComplete="email"
        />
        {mode !== "reset" && (
          <input
            type="password" value={pass} onChange={(e) => setPass(e.target.value)}
            placeholder="Password" style={field}
            autoComplete={mode === "login" ? "current-password" : "new-password"}
            onKeyDown={(e) => { if (e.key === "Enter") submit(); }}
          />
        )}

        <button
          onClick={submit} disabled={busy}
          className="cs-btn font-bold"
          style={{ background: "#3d50fa", color: "#fff", fontSize: 14.5, padding: "13px 16px", borderRadius: 10, marginTop: 4, opacity: busy ? 0.6 : 1 }}
        >
          {busy ? "Please wait…" : mode === "login" ? "Sign in" : mode === "signup" ? "Create account" : "Send reset link"}
        </button>

        {msg && (
          <p style={{ fontSize: 13, color: msg.ok ? "#48E484" : "#ea596e", textAlign: "center", lineHeight: 1.6 }}>{msg.text}</p>
        )}

        <div className="flex items-center justify-center" style={{ gap: 6, marginTop: 12, flexWrap: "wrap" }}>
          {mode !== "login" && (
            <button onClick={() => setMode("login")} className="cs-btn" style={{ color: "#3d50fa", fontSize: 13, fontWeight: 600 }}>Sign in</button>
          )}
          {mode !== "signup" && (
            <button onClick={() => setMode("signup")} className="cs-btn" style={{ color: "#3d50fa", fontSize: 13, fontWeight: 600 }}>Create account</button>
          )}
          {mode !== "reset" && (
            <button onClick={() => setMode("reset")} className="cs-btn" style={{ color: "#9ba0a4", fontSize: 13 }}>Forgot password?</button>
          )}
        </div>
      </div>

      <p style={{ color: "#6c7076", fontSize: 11.5, marginTop: 26, textAlign: "center", maxWidth: 320, lineHeight: 1.6 }}>
        Watch history, browse history & favourites account-er sathe save hobe.
      </p>

      {/* developer card — always visible, even on the login screen */}
      <DeveloperCard />
    </div>
  );
}

// --------------------------------------------------------- developer card

/**
 * Developer info card — Md. Khademul Islam.
 * Rendered on BOTH the login screen and the account/settings screen so the
 * developer info is never hidden behind auth state. The photo has a graceful
 * fallback (gradient "K" avatar) if the image ever fails to load.
 */
export function DeveloperCard() {
  const [imgFail, setImgFail] = useState(false);
  const badge = {
    fontSize: 10.5, padding: "4px 10px", borderRadius: 999,
    background: "#1C1C20", border: "1px solid #2B2C30", color: "#c9ccd1",
  } as React.CSSProperties;
  return (
    <div style={{ padding: "10px 13px 26px" }}>
      <p className="font-bold" style={{ fontSize: 12, letterSpacing: 1.4, color: "#9ba0a4", textTransform: "uppercase", margin: "0 2px 10px" }}>Developer</p>
      <div
        className="rounded-2xl relative overflow-hidden"
        style={{
          background: "linear-gradient(135deg, rgba(61,80,250,0.16) 0%, #141417 42%, #141417 100%)",
          border: "1px solid rgba(61,80,250,0.35)",
          padding: "18px 16px",
          display: "flex",
          alignItems: "center",
          gap: 16,
        }}
      >
        <div
          aria-hidden
          style={{
            position: "absolute", top: -34, right: -34, width: 110, height: 110, borderRadius: "50%",
            background: "radial-gradient(circle, rgba(61,80,250,0.35) 0%, rgba(61,80,250,0) 70%)",
          }}
        />
        {/* photo (with fallback avatar) */}
        <div style={{ position: "relative", flexShrink: 0, padding: 2.5, borderRadius: "50%", background: "linear-gradient(135deg, #3d50fa, #7b5cff 55%, #2ad0ff)" }}>
          {imgFail ? (
            <div className="rounded-full flex items-center justify-center font-bold" style={{ width: 70, height: 70, background: "#26272b", color: "#aab5ff", fontSize: 28 }}>
              K
            </div>
          ) : (
            // eslint-disable-next-line @next/next/no-img-element
            <img
              src="/dev/khademul.jpg"
              alt="Md. Khademul Islam"
              onError={() => setImgFail(true)}
              className="rounded-full"
              style={{ width: 70, height: 70, objectFit: "cover", objectPosition: "center 30%", display: "block" }}
            />
          )}
        </div>
        {/* info */}
        <div style={{ flex: 1, minWidth: 0 }}>
          <p className="font-bold" style={{ fontSize: 16.5, color: "#fff", lineHeight: 1.25 }}>Md. Khademul Islam</p>
          <p style={{ fontSize: 12, color: "#aab5ff", marginTop: 3, fontWeight: 600 }}>Developer &amp; Founder</p>
          <p style={{ fontSize: 11.5, color: "#9ba0a4", marginTop: 2 }}>SkStream · MLSBD + SouthFreak</p>
          <div className="flex" style={{ gap: 6, marginTop: 9, flexWrap: "wrap" }}>
            <span className="rounded-full font-bold" style={{ ...badge, background: "rgba(61,80,250,0.18)", border: "1px solid rgba(61,80,250,0.4)", color: "#aab5ff", letterSpacing: 0.4 }}>SKSTREAM</span>
            <span className="rounded-full" style={badge}>BDIX PROXY</span>
            <span className="rounded-full" style={badge}>MLSBD + SOUTHFREAK</span>
          </div>
        </div>
      </div>
      <p style={{ fontSize: 10.5, color: "#5c6066", marginTop: 10, textAlign: "center", lineHeight: 1.6 }}>
        SkStream v1.2 · account, history &amp; link-cache powered by Supabase + Cloudflare Workers
      </p>
    </div>
  );
}

// ---------------------------------------------------------- account screen

interface WatchRow {
  id: number; source: string; title: string; poster: string | null;
  detail_url: string; episode_index: number; episode_name: string | null;
  position_seconds: number; duration_seconds: number; updated_at: string;
}
interface BrowseRow {
  id: number; source: string | null; title: string | null; poster: string | null;
  url: string | null; query: string | null; created_at: string;
}
interface FavRow {
  id: number; source: string; title: string; poster: string | null;
  detail_url: string; created_at: string;
}

type Tab = "watch" | "browse" | "favs";

/** the owner account — gets the Admin menu in settings */
export const ADMIN_EMAIL = "yitube4k@gmail.com";

export function AccountScreen({ session, onOpen, onAdmin }: {
  session: SessionInfo;
  onOpen: (item: SearchItem, source: string) => void;
  onAdmin?: () => void;
}) {
  const [tab, setTab] = useState<Tab>("watch");
  const [watch, setWatch] = useState<WatchRow[]>([]);
  const [browse, setBrowse] = useState<BrowseRow[]>([]);
  const [favs, setFavs] = useState<FavRow[]>([]);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    setLoading(true);
    const [w, b, f] = await Promise.all([
      fetchRemoteHistory("watch") as Promise<WatchRow[]>,
      fetchRemoteHistory("browse") as Promise<BrowseRow[]>,
      fetchRemoteFavorites() as Promise<FavRow[]>,
    ]);
    setWatch(w); setBrowse(b); setFavs(f);
    setLoading(false);
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const signOut = async () => {
    const sb = getSupabaseBrowser();
    if (sb) await sb.auth.signOut();
    window.location.reload();
  };

  const clear = async (kind: Tab) => {
    if (kind === "watch") { await clearRemoteHistory("watch"); setWatch([]); }
    else if (kind === "browse") { await clearRemoteHistory("browse"); setBrowse([]); }
    else if (kind === "favs") {
      // favourites clear per-item; skip bulk for safety
    }
  };

  const srcDef = (id: string | null) => (id ? sourceById(id) : undefined);

  return (
    <div style={{ paddingBottom: 95 }}>
      {/* profile header */}
      <div className="px-[13px] pt-4 pb-3 flex items-center" style={{ gap: 12 }}>
        {session.avatar ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={session.avatar} alt="" className="rounded-full" style={{ width: 52, height: 52, objectFit: "cover" }} />
        ) : (
          <div className="rounded-full flex items-center justify-center font-bold" style={{ width: 52, height: 52, background: "#3d50fa", color: "#fff", fontSize: 20 }}>
            {(session.name || "U").slice(0, 1).toUpperCase()}
          </div>
        )}
        <div style={{ flex: 1, minWidth: 0 }}>
          <h1 className="font-bold" style={{ fontSize: 18, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{session.name}</h1>
          <p style={{ color: "#9ba0a4", fontSize: 12.5, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
            {session.email}{session.provider === "google" ? " · Google" : ""}
          </p>
        </div>
        <button onClick={signOut} className="cs-btn font-bold" style={{ border: "1px solid #2B2C30", color: "#ea596e", fontSize: 12.5, padding: "8px 14px", borderRadius: 8 }}>
          Sign out
        </button>
      </div>

      {/* tabs */}
      <div className="flex" style={{ padding: "0 13px", gap: 8, marginTop: 6, flexWrap: "wrap" }}>
        {([["watch", "Watch history"], ["browse", "Browse history"], ["favs", "Favourites"]] as [Tab, string][]).map(([id, label]) => (
          <button key={id} onClick={() => setTab(id)} className="cs-btn font-bold"
            style={{
              padding: "8px 14px", borderRadius: 20, fontSize: 12.5,
              background: tab === id ? "#3d50fa" : "#161616",
              color: tab === id ? "#fff" : "#9ba0a4",
              border: "1px solid " + (tab === id ? "#3d50fa" : "#1C1C20"),
            }}>
            {label}
          </button>
        ))}
      </div>

      {/* admin menu — owner only (yitube4k@gmail.com google login) */}
      {session.email.toLowerCase() === ADMIN_EMAIL.toLowerCase() && onAdmin && (
        <div className="px-[13px]" style={{ marginTop: 12 }}>
          <button
            onClick={onAdmin}
            className="cs-btn flex items-center w-full rounded-xl"
            style={{
              background: "linear-gradient(135deg, rgba(61,80,250,0.18), #161616 60%)",
              border: "1px solid rgba(61,80,250,0.45)",
              padding: "13px 15px",
              gap: 12,
              textAlign: "left",
            }}
          >
            <div className="rounded-lg flex items-center justify-center" style={{ width: 38, height: 38, background: "rgba(61,80,250,0.25)", flexShrink: 0 }}>
              <svg width="19" height="19" viewBox="0 0 24 24"><path fill="#aab5ff" d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 10.99h7c-.53 4.12-3.28 7.79-7 8.94V12H5V6.3l7-3.11v8.8z"/></svg>
            </div>
            <div style={{ flex: 1 }}>
              <p className="font-bold" style={{ fontSize: 14.5, color: "#fff" }}>Admin Panel</p>
              <p style={{ fontSize: 11.5, color: "#9ba0a4", marginTop: 1 }}>Users, browse/extract stats, 12h worker control</p>
            </div>
            <Icon d={P.forward} size={18} color="#6c7076" />
          </button>
        </div>
      )}

      <div style={{ padding: "12px 11px" }}>
        {loading && (
          <div className="flex flex-wrap" style={{ margin: -2 }}>
            {Array.from({ length: 6 }).map((_, i) => <PosterSkeleton key={i} />)}
          </div>
        )}

        {!loading && tab === "watch" && (
          watch.length === 0 ? (
            <Empty text="Kono watch history nei — ekta movie/stream play koro, resume point ekhane save hobe." />
          ) : (
            <>
              <div className="flex justify-end" style={{ marginBottom: 6 }}>
                <button onClick={() => clear("watch")} className="cs-btn" style={{ color: "#9ba0a4", fontSize: 12 }}>Clear all</button>
              </div>
              {watch.map((w) => {
                const pct = w.duration_seconds ? Math.min(100, (w.position_seconds / w.duration_seconds) * 100) : 0;
                return (
                  <button key={w.id} onClick={() => onOpen({ title: w.title, url: w.detail_url, poster: w.poster, type: "Movie" }, w.source)}
                    className="cs-btn flex items-center w-full rounded-xl" style={{ background: "#161616", border: "1px solid #1C1C20", padding: 10, marginBottom: 8, gap: 11, textAlign: "left" }}>
                    <div style={{ width: 86, height: 52, borderRadius: 8, overflow: "hidden", background: "#26272b", flexShrink: 0, position: "relative" }}>
                      {w.poster && (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img src={imgUrl(w.poster)} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} />
                      )}
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <p className="font-bold" style={{ fontSize: 13, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{w.title}</p>
                      <p style={{ fontSize: 11.5, color: "#9ba0a4", marginTop: 2 }}>
                        {srcDef(w.source)?.name || w.source}
                        {w.episode_name ? ` · ${w.episode_name}` : ""}
                        {` · ${fmtTime(w.position_seconds)} / ${fmtTime(w.duration_seconds)}`}
                      </p>
                      <div style={{ height: 3, background: "#26272b", borderRadius: 2, marginTop: 6 }}>
                        <div style={{ width: `${pct}%`, height: "100%", background: "#3d50fa", borderRadius: 2 }} />
                      </div>
                    </div>
                  </button>
                );
              })}
            </>
          )
        )}

        {!loading && tab === "browse" && (
          browse.length === 0 ? (
            <Empty text="Kono browse history nei — title khulle ba search korle ekhane joma hobe." />
          ) : (
            <>
              <div className="flex justify-end" style={{ marginBottom: 6 }}>
                <button onClick={() => clear("browse")} className="cs-btn" style={{ color: "#9ba0a4", fontSize: 12 }}>Clear all</button>
              </div>
              <div className="flex flex-wrap" style={{ margin: -2 }}>
                {browse.map((b) => (
                  <PosterCard key={b.id} item={{
                    title: b.title || b.query || "Untitled",
                    url: b.url || "",
                    poster: b.poster,
                    type: "Movie",
                  }} onClick={() => b.url && onOpen({ title: b.title || "", url: b.url, poster: b.poster, type: "Movie" }, b.source || "mlsbd")} />
                ))}
              </div>
              {browse.some((b) => !b.url) && (
                <>
                  <p className="font-bold" style={{ fontSize: 13, letterSpacing: 1, color: "#9ba0a4", textTransform: "uppercase", margin: "14px 2px 8px" }}>Searches</p>
                  <div className="flex flex-wrap" style={{ gap: 8 }}>
                    {browse.filter((b) => !b.url).slice(0, 30).map((b) => (
                      <span key={b.id} className="rounded-full" style={{ background: "#161616", border: "1px solid #1C1C20", color: "#c9ccd1", fontSize: 12.5, padding: "7px 14px" }}>
                        {b.query}
                      </span>
                    ))}
                  </div>
                </>
              )}
            </>
          )
        )}

        {!loading && tab === "favs" && (
          favs.length === 0 ? (
            <Empty text="Kono favourite nei — title page-er bookmark icon tap koro." />
          ) : (
            <div className="flex flex-wrap" style={{ margin: -2 }}>
              {favs.map((f) => (
                <PosterCard key={f.id} item={{ title: f.title, url: f.detail_url, poster: f.poster, type: "Movie" }}
                  onClick={() => onOpen({ title: f.title, url: f.detail_url, poster: f.poster, type: "Movie" }, f.source)} />
              ))}
            </div>
          )
        )}
      </div>

      {/* developer card */}
      <DeveloperCard />
    </div>
  );
}

function Empty({ text }: { text: string }) {
  return (
    <div className="flex flex-col items-center" style={{ paddingTop: "16vh", gap: 12 }}>
      <Icon d={P.library} size={44} color="#9ba0a4" />
      <span style={{ fontSize: 13, color: "#9ba0a4", textAlign: "center", lineHeight: 1.7, padding: "0 40px" }}>{text}</span>
    </div>
  );
}
