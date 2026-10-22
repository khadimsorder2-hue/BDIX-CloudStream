"use client";

// SkStream — app shell (CloudStream pixel-copy). Single visible route; view switching is
// client-side (like the app's fragments) with history back support.

import { useCallback, useEffect, useRef, useState } from "react";
import { HomeScreen, BottomNav, SourceFab, CsLogo, type ViewName } from "@/components/cs/HomeScreen";
import { SearchScreen, LibraryScreen, DownloadsScreen } from "@/components/cs/StaticScreens";
import { AccountScreen, AuthScreen, useSession } from "@/components/cs/AuthScreen";
import { AdminScreen } from "@/components/cs/AdminScreen";
import { ResultScreen } from "@/components/cs/ResultScreen";
import { PlayerOverlay } from "@/components/cs/PlayerOverlay";
import { getProgressMap, type Detail, type Episode, type SearchItem } from "@/components/cs/store";

type View =
  | { name: ViewName }
  | { name: "result"; item: SearchItem; source: string }
  | { name: "admin" };

interface Playing {
  detail: Detail;
  episodeIndex: number;
  episodes: Episode[];
}

export default function Page() {
  const { session, loading } = useSession();
  const [stack, setStack] = useState<View[]>([{ name: "home" }]);
  const [playing, setPlaying] = useState<Playing | null>(null);
  const view = stack[stack.length - 1];

  // ---- history back system (guard-entry trap) ---------------------------
  // Single guard entry rides on top: back from any in-app view/player is
  // consumed by popstate (view pop / player close) and the guard is
  // re-pushed, so Android back never leaves the site mid-navigation. At the
  // home root the browser back behaves normally (leaves the page).
  const stackLen = useRef(1);
  const playingRef = useRef<Playing | null>(null);
  const lastPlayerClose = useRef(0); // dedupe fullscreenchange vs popstate
  stackLen.current = stack.length;
  playingRef.current = playing;

  const push = useCallback((v: View) => {
    setStack((s) => [...s, v]);
  }, []);

  const goBack = useCallback(() => {
    setStack((s) => (s.length > 1 ? s.slice(0, -1) : s));
  }, []);

  const closePlaying = useCallback(() => {
    lastPlayerClose.current = Date.now();
    setPlaying(null);
  }, []);

  useEffect(() => {
    let alive = false;
    const guardState = () => ({ ...(window.history.state || {}), cs: "guard" });
    try {
      if (!window.history.state?.cs) {
        window.history.replaceState({ ...(window.history.state || {}), cs: "root" }, "");
        window.history.pushState(guardState(), "");
      }
    } catch { /* private mode etc. */ }
    alive = true;

    const onPop = () => {
      if (!alive) return;
      // player just closed via its own fullscreenchange handler (Android back
      // in landscape exits fullscreen WITHOUT popstate; some browsers fire
      // both) — swallow the duplicate event.
      if (Date.now() - lastPlayerClose.current < 700) {
        try { window.history.pushState(guardState(), ""); } catch {}
        return;
      }
      const playerOpen = !!playingRef.current;
      const deeper = stackLen.current > 1;
      if (playerOpen || deeper) {
        try { window.history.pushState(guardState(), ""); } catch {}
        if (playerOpen) { closePlaying(); return; }
        setStack((s) => (s.length > 1 ? s.slice(0, -1) : s));
      }
      // at home root: normal browser back (page leaves — expected)
    };
    window.addEventListener("popstate", onPop);
    return () => {
      alive = false;
      window.removeEventListener("popstate", onPop);
    };
  }, [closePlaying]);

  const openItem = useCallback((item: SearchItem, source: string) => {
    push({ name: "result", item, source });
  }, [push]);

  const activeName: ViewName = view.name === "result" || view.name === "admin" ? (view.name === "admin" ? "settings" : "home") : view.name;

  // ---------- auth gate: no account → nothing streams
  if (loading) {
    return (
      <div className="cs-app flex flex-col items-center justify-center" style={{ minHeight: "100vh", gap: 14 }}>
        <CsLogo size={46} />
        <div className="cs-spinner" />
      </div>
    );
  }
  if (!session) return <AuthScreen />;

  return (
    <div className="cs-app">
      {view.name === "home" && (
        <HomeScreen onOpen={openItem} onOpenSource={(s) => { if (s === "__settings__") push({ name: "settings" }); }} />
      )}
      {view.name === "search" && <SearchScreen onOpen={openItem} />}
      {view.name === "library" && <LibraryScreen onOpen={openItem} />}
      {view.name === "downloads" && <DownloadsScreen />}
      {view.name === "settings" && session && (
        <AccountScreen
          session={session}
          onOpen={openItem}
          onAdmin={() => push({ name: "admin" })}
        />
      )}
      {view.name === "admin" && session && <AdminScreen onBack={goBack} />}
      {view.name === "result" && (
        <ResultScreen
          key={view.item.url}
          item={view.item}
          source={view.source}
          onBack={goBack}
          onPlay={({ detail, episodeIndex, episodes }) => {
            setPlaying({ detail, episodeIndex, episodes });
          }}
        />
      )}

      {view.name !== "result" && (
        <>
          <BottomNav active={activeName} onChange={(v) => { setStack([{ name: v }]); window.scrollTo(0, 0); }} />
          <SourceFab />
        </>
      )}

      {playing && (
        <PlayerOverlay
          detail={playing.detail}
          episodeIndex={playing.episodeIndex}
          onClose={closePlaying}
          onEpisodeSwitch={(i) => {
            setPlaying((p) => (p ? { ...p, episodeIndex: i } : p));
          }}
        />
      )}
    </div>
  );
}
