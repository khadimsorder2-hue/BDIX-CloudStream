"use client";

// CloudStream home: logo top bar, Continue Watching row, per-source lazy
// rows, 5-tab bottom nav (official bottom_nav_menu.xml) + floating source
// selector FAB.

import { useCallback, useEffect, useMemo, useState } from "react";
import { Icon, P } from "./icons";
import { PosterCard, HomeRowView } from "./PosterRow";
import {
  SOURCES, sourceById, getProgressMap, imgUrl,
  useSourcePreference, useLocalListener,
  type SearchItem, type ProgressEntry,
} from "./store";

export type ViewName = "home" | "search" | "library" | "downloads" | "settings";

export function CsLogo({ size = 26 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <defs>
        <linearGradient id="cslogo" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor="#3d50fa" />
          <stop offset="100%" stopColor="#8b5cf6" />
        </linearGradient>
      </defs>
      <path
        fill="url(#cslogo)"
        d="M19.35 10.04A7.49 7.49 0 0 0 12 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 0 0 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM10 17l-3.5-3.5 1.41-1.41L10 14.17 15.09 9.08 16.5 10.5z"
      />
    </svg>
  );
}

export function TopBar({ onOpenSettings }: { onOpenSettings: () => void }) {
  return (
    <div
      className="sticky top-0 z-20 flex items-center justify-between px-[13px] py-[9px]"
      style={{ background: "rgba(17,17,17,.92)", backdropFilter: "blur(8px)" }}
    >
      <div className="flex items-center gap-2">
        <CsLogo />
        <span className="font-bold tracking-wide" style={{ fontSize: 16.5, color: "#e9eaee" }}>
          SkStream
        </span>
        <span className="rounded-full font-bold" style={{ fontSize: 9, background: "#3d50fa", color: "#fff", padding: "2px 7px", marginLeft: 2 }}>
          WEB
        </span>
      </div>
      <button onClick={onOpenSettings} className="cs-btn p-2 rounded-full" aria-label="Settings" style={{ color: "#9ba0a6" }}>
        <Icon d={P.settings} />
      </button>
    </div>
  );
}

export function BottomNav({ active, onChange }: { active: ViewName; onChange: (v: ViewName) => void }) {
  const items: [ViewName, string][] = [
    ["home", P.home],
    ["search", P.search],
    ["library", P.library],
    ["downloads", P.download],
    ["settings", P.settings],
  ];
  return (
    <nav
      className="fixed bottom-0 left-0 right-0 z-30 flex items-stretch"
      style={{ height: 62, background: "#161616", borderTop: "1px solid rgba(255,255,255,.05)", paddingBottom: "env(safe-area-inset-bottom,0px)" }}
      aria-label="Main navigation"
    >
      {items.map(([id, icon]) => {
        const on = active === id;
        return (
          <button
            key={id}
            onClick={() => onChange(id)}
            className="flex-1 flex flex-col items-center justify-center gap-[3px]"
            style={{ color: on ? "#3d50fa" : "#9ba0a6", transition: "color .15s" }}
            aria-label={id}
            aria-current={on ? "page" : undefined}
          >
            <Icon d={icon} size={22} />
            <span style={{ fontSize: 10, fontWeight: on ? 700 : 500, textTransform: "capitalize" }}>{id}</span>
          </button>
        );
      })}
    </nav>
  );
}

// ------------------------------------------------- floating source selector

export function SourceFab() {
  const { source, setSource } = useSourcePreference();
  const [open, setOpen] = useState(false);
  const active = source === "all" ? null : sourceById(source);

  return (
    <>
      {open && <div className="fixed inset-0 z-40 cs-fade" style={{ background: "rgba(0,0,0,.55)" }} onClick={() => setOpen(false)} />}
      <button
        onClick={() => setOpen(true)}
        className="cs-fab fixed z-40 flex items-center justify-center rounded-2xl"
        style={{
          right: 16, bottom: 78, width: 52, height: 52,
          background: "#1C1C20", border: "1.5px solid #2B2C30",
        }}
        aria-label="Select source"
      >
        {active ? (
          <span
            className="flex items-center justify-center rounded-full font-bold"
            style={{ width: 26, height: 26, background: active.color, color: "#111", fontSize: 14 }}
          >
            {active.short}
          </span>
        ) : (
          <Icon d={P.sources} size={22} color="#e9eaee" />
        )}
        <span
          className="absolute flex items-center justify-center rounded-full"
          style={{ top: -3, right: -3, width: 16, height: 16, background: "#3d50fa" }}
        >
          <Icon d={P.swap} size={11} color="#fff" />
        </span>
      </button>
      {open && (
        <div
          className="cs-sheet fixed left-0 right-0 bottom-0 z-50 mx-auto rounded-t-2xl"
          style={{ background: "#161616", maxWidth: 560, paddingBottom: "calc(14px + env(safe-area-inset-bottom,0px))" }}
        >
          <div className="flex items-center justify-between px-5 pt-4 pb-2">
            <h3 className="font-bold" style={{ fontSize: 15.5, color: "#e9eaee" }}>Sources</h3>
            <button onClick={() => setOpen(false)} className="cs-btn p-2 rounded-full" style={{ color: "#9ba0a6" }} aria-label="Close">
              <Icon d={P.close} size={20} />
            </button>
          </div>
          <div className="px-2.5 pb-2">
            <SourceRow
              name="All sources" sub="Rows from every installed source"
              color="#3d50fa" short="A" selected={source === "all"}
              onClick={() => { setSource("all"); setOpen(false); }}
            />
            {SOURCES.map((s) => (
              <SourceRow
                key={s.id}
                name={s.name} sub={s.lang === "bn" ? "Bangla · Movies & Series" : "Hindi · Movies & Series"}
                color={s.color} short={s.short} selected={source === s.id}
                onClick={() => { setSource(s.id); setOpen(false); }}
              />
            ))}
          </div>
        </div>
      )}
    </>
  );
}

function SourceRow({ name, sub, color, short, selected, onClick }: {
  name: string; sub: string; color: string; short: string; selected: boolean; onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      className="w-full flex items-center gap-4 px-3 py-3 rounded-xl hover:bg-white/5 text-left"
      style={{ color: selected ? "#3d50fa" : "#e9eaee" }}
    >
      <span
        className="flex items-center justify-center rounded-full font-bold shrink-0"
        style={{ width: 38, height: 38, background: color, color: "#111", fontSize: 17 }}
      >
        {short}
      </span>
      <span className="flex-1 min-w-0">
        <span className="block font-semibold" style={{ fontSize: 14.5, fontWeight: selected ? 700 : 500 }}>{name}</span>
        <span className="block" style={{ fontSize: 12, color: "#9ba0a4" }}>{sub}</span>
      </span>
      {selected && <Icon d={P.check} size={20} color="#3d50fa" />}
    </button>
  );
}

// ------------------------------------------------------------- home view

export function HomeScreen({ onOpen, onOpenSource }: {
  onOpen: (item: SearchItem, source: string) => void;
  onOpenSource: (source: string) => void;
}) {
  const { source } = useSourcePreference();
  const [continueList, setContinueList] = useState<ProgressEntry[]>([]);

  const refreshContinue = useCallback(() => {
    const map = getProgressMap();
    const list = Object.values(map)
      .filter((e) => e.position > 30 && e.duration > 0 && e.position < e.duration * 0.97)
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .slice(0, 12);
    setContinueList(list);
  }, []);

  useEffect(() => {
    const id = requestAnimationFrame(refreshContinue);
    const h = () => refreshContinue();
    window.addEventListener("cs-progress", h);
    return () => {
      cancelAnimationFrame(id);
      window.removeEventListener("cs-progress", h);
    };
  }, [refreshContinue]);

  const activeSources = useMemo(
    () => (source === "all" ? SOURCES : SOURCES.filter((s) => s.id === source)),
    [source]
  );

  return (
    <div style={{ paddingBottom: 90 }}>
      <TopBar onOpenSettings={() => onOpenSource("__settings__")} />
      {continueList.length > 0 && (
        <div style={{ marginBottom: 14 }}>
          <div className="flex items-center justify-between px-[13px] pb-[6px] pt-[3px]">
            <span className="font-bold flex items-center" style={{ fontSize: 15.5, gap: 8 }}>
              <span aria-hidden style={{ width: 3, height: 15, borderRadius: 2, background: "linear-gradient(180deg,#3d50fa,#8b5cf6)", display: "inline-block" }} />
              Continue Watching
            </span>
          </div>
          <div className="flex overflow-x-auto cs-no-scrollbar" style={{ padding: "2px 11px" }}>
            {continueList.map((p) => (
              <PosterCard
                key={p.url + p.episodeIndex}
                width={150}
                item={{ title: p.title, url: p.url, poster: p.poster, type: "Movie" }}
                progress={p}
                onClick={() => onOpen({ title: p.title, url: p.url, poster: p.poster, type: "Movie" }, p.source)}
              />
            ))}
          </div>
        </div>
      )}
      {activeSources.map((s) =>
        s.categories.map(([cat, title]) => (
          <HomeRowView
            key={s.id + cat}
            title={title}
            source={s.id}
            category={cat}
            onOpen={onOpen}
            onOpenSource={onOpenSource}
          />
        ))
      )}
      {/* brand footer */}
      <div className="flex flex-col items-center" style={{ padding: "18px 0 4px", gap: 6 }}>
        <div className="flex items-center" style={{ gap: 7 }}>
          <CsLogo size={15} />
          <span style={{ fontSize: 11, color: "#5c6066", fontWeight: 600, letterSpacing: 1.2 }}>SKSTREAM</span>
        </div>
        <span style={{ fontSize: 10, color: "#43464c" }}>Fast BDIX streaming · skstream.pages.dev</span>
      </div>
    </div>
  );
}
