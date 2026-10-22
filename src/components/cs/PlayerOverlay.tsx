"use client";

// Player overlay: loads the standalone /player/player.css + player.js module
// and mounts window.SkStreamPlayer with resolved links. The player code
// itself lives 100% in public/player/ (separate deliverable).

import { useCallback, useEffect, useRef, useState } from "react";
import {
  fetchLinks, saveProgress, getProgressMap, imgUrl,
  type Detail, type Episode, type StreamLink,
} from "./store";
import { exitLandscapeNow } from "@/lib/landscape";

declare global {
  interface Window {
    SkStreamPlayer?: {
      mount: (el: HTMLElement, opts: Record<string, unknown>) => PlayerController;
      version: string;
      hevcSupported: () => boolean;
    };
  }
}
interface PlayerController {
  destroy: () => void;
  setLinkIndex: (i: number) => void;
  setPosition: (t: number) => void;
}

let assetsLoaded = false;
// one-shot auto-refresh guard (module scope): a stale-cache movie gets ONE
// silent re-extraction; if it still fails the player only offers manual
// buttons — prevents an infinite remount loop
let lastAutoRefreshKey = "";
function ensureAssets(): Promise<void> {
  if (assetsLoaded) return Promise.resolve();
  return new Promise((resolve) => {
    const link = document.createElement("link");
    link.rel = "stylesheet";
    link.href = "/player/player.css";
    document.head.appendChild(link);
    const s = document.createElement("script");
    s.src = "/player/player.js";
    s.onload = () => { assetsLoaded = true; resolve(); };
    s.onerror = () => resolve();
    document.head.appendChild(s);
  });
}

export function PlayerOverlay({
  detail, episodeIndex, onClose, onEpisodeSwitch,
}: {
  detail: Detail;
  episodeIndex: number;
  onClose: () => void;
  onEpisodeSwitch: (i: number) => void;
}) {
  const stage = useRef<HTMLDivElement>(null);
  const ctrl = useRef<PlayerController | null>(null);
  const [phase, setPhase] = useState<"loading" | "ready" | "error">("loading");
  const [errText, setErrText] = useState("");
  const [epIdx, setEpIdx] = useState(episodeIndex);

  const episodes = detail.episodes ?? [];

  const mountWith = useCallback(async (ep: Episode | null, idx: number, refresh = false) => {
    setPhase("loading");
    setEpIdx(idx);
    try {
      const data = ep ? ep.data : detail.movieData || "";
      const links: StreamLink[] = await fetchLinks(detail.source, data, detail.title, refresh);
      const playable = links.filter((l) => !l.isPage);
      const pages = links.filter((l) => l.isPage);
      const all = [...playable, ...pages];
      if (!all.length) {
        setErrText("No sources could be extracted for this title.");
        setPhase("error");
        return;
      }
      const resumeMap = getProgressMap();
      const key = detail.url + (ep ? `#${idx}` : "");
      const saved = resumeMap[key];
      const allowAutoRefresh = lastAutoRefreshKey !== key;
      const subtitle = ep ? ep.name : (detail.contentQuality || "");
      if (ctrl.current) { try { ctrl.current.destroy(); } catch { /* old mount */ } }
      ctrl.current = window.SkStreamPlayer!.mount(stage.current!, {
        links: all,
        startIndex: 0,
        title: detail.title,
        subtitle,
        autoPlay: true,
        skipIntro: [0, 85],
        resumePosition: saved && saved.position > 30 ? saved.position : 0,
        episodes: episodes.map((e, i) => ({ name: e.name, index: i })),
        episodeIndex: idx,
        onEpisode: (i: number) => {
          const nextEp = episodes[i];
          if (nextEp) mountWith(nextEp, i);
          else onEpisodeSwitch(i);
        },
        onBack: () => onClose(),
        // player auto-refresh: after every source failed (stale cache links are
        // the usual cause) re-extract ONCE with refresh=1 and remount; the
        // player then keeps a manual "Refresh sources" button for later tries
        autoRefresh: allowAutoRefresh,
        onRefreshSources: () => {
          lastAutoRefreshKey = key;
          mountWith(ep, idx, true);
        },
        onProgress: (p: { position: number; duration: number; linkIndex: number; finished?: boolean }) => {
          if (!p.duration) return;
          saveProgress({
            url: detail.url + (episodes.length ? `#${idx}` : ""),
            source: detail.source,
            title: detail.title,
            poster: detail.poster,
            position: p.position,
            duration: p.duration,
            episodeIndex: idx,
            episodeName: ep ? ep.name : "",
            updatedAt: Date.now(),
          });
          window.dispatchEvent(new Event("cs-progress"));
        },
      });
      setPhase("ready");
    } catch (e) {
      setErrText(String(e));
      setPhase("error");
    }
  }, [detail, episodes, onClose, onEpisodeSwitch]);

  useEffect(() => {
    let alive = true;
    ensureAssets().then(() => {
      if (!alive || !stage.current) return;
      if (!window.SkStreamPlayer) {
        setErrText("Player module failed to load.");
        setPhase("error");
        return;
      }
      const ep = episodes[epIdx] ?? null;
      mountWith(ep, epIdx);
    });
    return () => {
      alive = false;
      if (ctrl.current) { ctrl.current.destroy(); ctrl.current = null; }
      // leaving the player always returns the app to portrait
      exitLandscapeNow();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const retry = () => {
    if (ctrl.current) { ctrl.current.destroy(); ctrl.current = null; }
    mountWith(episodes[epIdx] ?? null, epIdx);
  };

  return (
    <div className="fixed inset-0 z-[100] cs-fade" style={{ background: "#000" }}>
      <div ref={stage} style={{ position: "absolute", inset: 0 }} />
      {phase === "loading" && (
        <div className="absolute inset-0 flex flex-col items-center justify-center" style={{ gap: 16 }}>
          {detail.poster && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={imgUrl(detail.poster)} alt="" className="absolute inset-0 w-full h-full object-cover opacity-25" />
          )}
          <div className="cs-spinner relative" />
          <p className="relative" style={{ color: "#9ba0a4", fontSize: 13.5 }}>Fetching sources…</p>
        </div>
      )}
      {phase === "error" && (
        <div className="absolute inset-0 flex flex-col items-center justify-center" style={{ gap: 12, padding: 30, background: "rgba(0,0,0,.85)" }}>
          <p style={{ color: "#e9eaee", fontSize: 15, textAlign: "center" }}>{errText}</p>
          <div className="flex" style={{ gap: 10 }}>
            <button onClick={retry} className="cs-btn" style={{ background: "#3d50fa", color: "#fff", fontWeight: 700, padding: "10px 22px", borderRadius: 8 }}>
              Retry
            </button>
            <button onClick={onClose} className="cs-btn" style={{ border: "1px solid rgba(255,255,255,.3)", color: "#e9eaee", fontWeight: 700, padding: "10px 22px", borderRadius: 8 }}>
              Back
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
