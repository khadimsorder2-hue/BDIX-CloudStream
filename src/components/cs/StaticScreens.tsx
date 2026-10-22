"use client";

// Search + Library + Downloads + Settings screens (CloudStream styling).

import { useCallback, useEffect, useRef, useState } from "react";
import { Icon, P } from "./icons";
import { PosterCard, PosterSkeleton } from "./PosterRow";
import { CsLogo } from "./HomeScreen";
import {
  fetchSearch, getBookmarks, getProgressMap, sourceById, recordSearch,
  useSourcePreference, type SearchItem,
} from "./store";

export function SearchScreen({ onOpen }: { onOpen: (item: SearchItem, source: string) => void }) {
  const { source } = useSourcePreference();
  const [q, setQ] = useState("");
  const [groups, setGroups] = useState<{ source: string; name: string; color: string; items: SearchItem[] }[]>([]);
  const [loading, setLoading] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout>>(null);

  const run = useCallback(async (query: string, src: string) => {
    if (!query.trim()) { setGroups([]); return; }
    recordSearch(query.trim()); // search history → Supabase
    setLoading(true);
    try {
      const res = await fetchSearch(query, src);
      setGroups(res.groups || []);
    } finally {
      setLoading(false);
    }
  }, []);

  const onChange = (v: string) => {
    setQ(v);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => run(v, source), 450);
  };

  return (
    <div style={{ paddingBottom: 95 }}>
      <div className="sticky top-0 z-20 flex items-center" style={{ padding: "10px 12px", gap: 8, background: "rgba(17,17,17,.94)", backdropFilter: "blur(8px)" }}>
        <input
          autoFocus
          value={q}
          onChange={(e) => onChange(e.target.value)}
          placeholder="Search…"
          className="flex-1 outline-none"
          style={{
            background: "#303135", color: "#e9eaee", borderRadius: 8,
            border: "1px solid #2B2C30", fontSize: 14.5, padding: "12px 15px",
          }}
          aria-label="Search titles"
        />
        {q && (
          <button onClick={() => { setQ(""); setGroups([]); }} className="cs-btn p-2 rounded-full" style={{ color: "#9ba0a6" }} aria-label="Clear">
            <Icon d={P.close} />
          </button>
        )}
      </div>

      <div style={{ padding: "10px 11px" }}>
        {loading && (
          <div className="flex flex-wrap" style={{ margin: -2 }}>
            {Array.from({ length: 9 }).map((_, i) => <PosterSkeleton key={i} />)}
          </div>
        )}
        {!loading && q && groups.length === 0 && (
          <div className="flex flex-col items-center" style={{ paddingTop: "22vh", gap: 10, color: "#9ba0a4" }}>
            <Icon d={P.search} size={44} />
            <span style={{ fontSize: 14 }}>No results for “{q}”</span>
          </div>
        )}
        {!q && !loading && (
          <div className="flex flex-col items-center" style={{ paddingTop: "24vh", gap: 10, color: "#9ba0a4" }}>
            <Icon d={P.search} size={44} />
            <span style={{ fontSize: 14 }}>Search across your sources</span>
          </div>
        )}
        {groups.map((g) => (
          <div key={g.source} style={{ marginBottom: 16 }}>
            <div className="flex items-center gap-2 px-1" style={{ paddingBottom: 7 }}>
              <span className="rounded-full flex items-center justify-center font-bold" style={{ width: 20, height: 20, background: g.color, color: "#111", fontSize: 11 }}>
                {sourceById(g.source)?.short}
              </span>
              <span className="font-bold" style={{ fontSize: 14.5 }}>{g.name}</span>
              <span style={{ fontSize: 12, color: "#9ba0a4" }}>{g.items.length}</span>
            </div>
            <div className="flex flex-wrap" style={{ margin: -2 }}>
              {g.items.map((it) => (
                <PosterCard key={g.source + it.url} item={it} onClick={() => onOpen(it, g.source)} />
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ library

export function LibraryScreen({ onOpen }: { onOpen: (item: SearchItem, source: string) => void }) {
  const [books, setBooks] = useState<SearchItem[]>([]);
  const refresh = useCallback(() => setBooks(getBookmarks()), []);
  useEffect(() => {
    const id = requestAnimationFrame(refresh);
    window.addEventListener("cs-source-change", refresh);
    return () => {
      cancelAnimationFrame(id);
      window.removeEventListener("cs-source-change", refresh);
    };
  }, [refresh]);

  return (
    <div style={{ paddingBottom: 95 }}>
      <div className="px-[13px] pt-4 pb-3">
        <h1 className="font-bold" style={{ fontSize: 19 }}>Library</h1>
        <p style={{ color: "#9ba0a4", fontSize: 13, marginTop: 3 }}>Bookmarked titles</p>
      </div>
      {books.length === 0 ? (
        <EmptyState icon={P.library} title="No bookmarks yet" text="Tap the bookmark icon on any title to save it here." />
      ) : (
        <div className="flex flex-wrap" style={{ padding: "0 9px", margin: -2 }}>
          {books.map((b) => (
            <PosterCard key={b.url} item={b} onClick={() => onOpen(b, "mlsbd")} />
          ))}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- downloads

export function DownloadsScreen() {
  const n = Object.keys(getProgressMap()).length;
  return (
    <div style={{ paddingBottom: 95 }}>
      <div className="px-[13px] pt-4 pb-3">
        <h1 className="font-bold" style={{ fontSize: 19 }}>Downloads</h1>
        <p style={{ color: "#9ba0a4", fontSize: 13, marginTop: 3 }}>Offline viewing</p>
      </div>
      <EmptyState
        icon={P.download}
        title="No downloads"
        text={n > 0
          ? `You have ${n} title${n > 1 ? "s" : ""} in Continue Watching — downloads are served from the source host pages (download buttons in each source's site).`
          : "Download buttons live on the source host pages — open any title and pick a download host from the player's source menu."}
      />
    </div>
  );
}

// ----------------------------------------------------------------- settings
// (emptied per request — the old Device capability / Sources / About cards
//  are gone; the settings tab now shows the Supabase account hub:
//  AuthScreen when signed out, AccountScreen when signed in — see page.tsx)

function EmptyState({ icon, title, text }: { icon: string; title: string; text: string }) {
  return (
    <div className="flex flex-col items-center" style={{ paddingTop: "20vh", gap: 12, padding: "18vh 40px 0" }}>
      <Icon d={icon} size={46} color="#9ba0a4" />
      <span className="font-bold" style={{ fontSize: 15.5, color: "#e9eaee" }}>{title}</span>
      <span style={{ fontSize: 13, color: "#9ba0a4", textAlign: "center", lineHeight: 1.6 }}>{text}</span>
    </div>
  );
}
