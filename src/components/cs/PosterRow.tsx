"use client";

// Poster card (114×180dp pixel-copy of home_result_grid.xml) + horizontal
// lazy row (home rows with shimmer + infinite page append) — CloudStream home.

import { useCallback, useEffect, useRef, useState } from "react";
import { Icon, P } from "./icons";
import {
  fetchHome, imgUrl, sourceById,
  type HomeRowData, type SearchItem, type ProgressEntry,
} from "./store";

const QUALITY_GUESS = (title: string): string => {
  const t = title.toLowerCase();
  if (t.includes("2160") || t.includes("4k")) return "4K";
  if (t.includes("1080")) return "1080p";
  if (t.includes("720")) return "720p";
  if (t.includes("480")) return "480p";
  if (t.includes("hevc")) return "HEVC";
  return "";
};

export function PosterCard({
  item, onClick, progress, width = 114,
}: {
  item: SearchItem;
  onClick: () => void;
  progress?: ProgressEntry | null;
  width?: number;
}) {
  const [err, setErr] = useState(false);
  const q = QUALITY_GUESS(item.title);
  const pct = progress && progress.duration > 0
    ? Math.min(100, Math.round((progress.position / progress.duration) * 100)) : 0;
  return (
    <div
      className="cs-poster-card shrink-0 relative"
      style={{ width, aspectRatio: "114/180", margin: 2, borderRadius: 10, background: "#2B2C30", boxShadow: "0 3px 10px rgba(0,0,0,.4)" }}
      onClick={onClick}
      role="button"
      aria-label={item.title}
    >
      {item.poster && !err ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={imgUrl(item.poster)}
          alt={item.title}
          loading="lazy"
          className="w-full h-full object-cover"
          style={{ borderRadius: 10 }}
          onError={() => setErr(true)}
        />
      ) : (
        <div className="w-full h-full flex items-center justify-center" style={{ borderRadius: 10 }}>
          <Icon d={P.info} size={30} color="#9ba0a4" />
        </div>
      )}
      {/* bottom shadow + title (title_shadow 50dp) */}
      <div
        className="absolute bottom-0 left-0 right-0 flex items-end justify-center pb-[5px] px-[5px]"
        style={{ height: 50, borderRadius: "0 0 10px 10px", background: "linear-gradient(to top, rgba(0,0,0,.82), rgba(0,0,0,0))" }}
      >
        <span
          className="font-bold text-center leading-tight"
          style={{ color: "#e9eaee", fontSize: width >= 110 ? 12 : 11.5, textShadow: "0 1px 2px rgba(0,0,0,.8)", display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}
        >
          {item.title}
        </span>
      </div>
      {/* quality chip top-left (TypeButton) */}
      {q && (
        <span
          className="absolute font-bold"
          style={{ top: 6, left: 6, background: "#3d50fa", color: "#fff", fontSize: 9.5, padding: "2.5px 6px", borderRadius: 5, letterSpacing: .3 }}
        >
          {q}
        </span>
      )}
      {/* continue watching: circular ring + play */}
      {progress && pct > 0 && (
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
          <div className="relative flex items-center justify-center" style={{ width: 46, height: 46 }}>
            <svg width="46" height="46" viewBox="0 0 46 46" className="absolute inset-0">
              <circle cx="23" cy="23" r="20" stroke="rgba(0,0,0,.55)" strokeWidth="2.5" fill="rgba(0,0,0,.25)" />
              <circle
                cx="23" cy="23" r="20"
                stroke="#3d50fa" strokeWidth="2.5" fill="none"
                strokeDasharray={`${(pct / 100) * 2 * Math.PI * 20} 999`}
                strokeLinecap="round"
                transform="rotate(-90 23 23)"
              />
            </svg>
            <Icon d={P.play} size={22} color="#fff" />
          </div>
        </div>
      )}
    </div>
  );
}

export function PosterSkeleton({ width = 114 }: { width?: number }) {
  return (
    <div className="cs-shimmer shrink-0" style={{ width, aspectRatio: "114/180", margin: 2, borderRadius: 10 }} />
  );
}

// ------------------------------------------------------------ lazy row

export function HomeRowView({
  title, source, category, onOpen, onOpenSource,
}: {
  title: string;
  source: string;
  category: string;
  onOpen: (item: SearchItem, source: string) => void;
  onOpenSource?: (source: string) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const [rows, setRows] = useState<HomeRowData | null>(null);
  const [page, setPage] = useState(1);
  const [loadingMore, setLoadingMore] = useState(false);
  const visible = useRef(false);

  const load = useCallback(async (p: number, append: boolean) => {
    const data = await fetchHome(source, category, p);
    setRows((prev) => {
      if (!prev || !append || data.page <= prev.page) return data;
      const seen = new Set(prev.items.map((i) => i.url));
      return { ...data, items: [...prev.items, ...data.items.filter((i) => !seen.has(i.url))] };
    });
  }, [source, category]);

  // lazy first load
  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    const io = new IntersectionObserver((entries) => {
      if (entries[0].isIntersecting && !visible.current) {
        visible.current = true;
        load(1, false).catch(() => {});
      }
    }, { rootMargin: "300px" });
    io.observe(node);
    return () => io.disconnect();
  }, [load]);

  // infinite horizontal append
  const onScroll = useCallback(() => {
    const el = scroller.current;
    if (!el || !rows?.hasNext || loadingMore) return;
    if (el.scrollLeft + el.clientWidth > el.scrollWidth - 400) {
      setLoadingMore(true);
      const next = page + 1;
      load(next, true).finally(() => { setPage(next); setLoadingMore(false); });
    }
  }, [rows, loadingMore, page, load]);

  const def = sourceById(source);

  return (
    <div ref={ref} style={{ marginBottom: 14 }}>
      <div className="flex items-center justify-between px-[13px] pb-[6px] pt-[3px]">
        <span className="font-bold flex items-center" style={{ color: "#e9eaee", fontSize: 15.5, gap: 8 }}>
          <span aria-hidden style={{ width: 3, height: 15, borderRadius: 2, background: "linear-gradient(180deg,#3d50fa,#8b5cf6)", display: "inline-block" }} />
          {title}
        </span>
        {def && (
          <button
            onClick={() => onOpenSource?.(source)}
            className="flex items-center gap-1.5 opacity-80 hover:opacity-100"
            style={{ fontSize: 11.5, color: "#9ba0a4" }}
          >
            <span
              className="flex items-center justify-center font-bold rounded-full"
              style={{ width: 18, height: 18, background: def.color, color: "#111", fontSize: 10 }}
            >
              {def.short}
            </span>
            {def.name}
            <Icon d={P.chevR} size={13} />
          </button>
        )}
      </div>
      <div
        ref={scroller}
        onScroll={onScroll}
        className="flex overflow-x-auto cs-no-scrollbar"
        style={{ padding: "2px 11px" }}
      >
        {!rows ? (
          Array.from({ length: 7 }).map((_, i) => <PosterSkeleton key={i} />)
        ) : rows.items.length === 0 ? (
          <div className="px-2 py-6" style={{ color: "#9ba0a4", fontSize: 13 }}>No items found</div>
        ) : (
          <>
            {rows.items.map((it) => (
              <PosterCard key={it.url} item={it} onClick={() => onOpen(it, source)} />
            ))}
            {rows.hasNext && loadingMore && Array.from({ length: 4 }).map((_, i) => <PosterSkeleton key={"m" + i} />)}
          </>
        )}
      </div>
    </div>
  );
}
