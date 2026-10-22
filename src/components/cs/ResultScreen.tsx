"use client";

// CloudStream result/details page — pixel-copy of fragment_result.xml:
// 200dp backdrop + gradient, 100×140 poster, 20sp bold title, FlowLayout
// meta, WhiteButton Play, bookmark, expandable description, tags chips,
// episode cards (50dp, radius 10, circular play progress).

import { useCallback, useEffect, useState } from "react";
import { Icon, P } from "./icons";
import {
  fetchDetail, fetchLinks, imgUrl, isBookmarked, toggleBookmark, recordBrowse,
  fmtTime, sourceById, type Detail, type Episode, type SearchItem, type StreamLink,
} from "./store";
import { enterLandscapeNow } from "@/lib/landscape";

export function ShimmerResult() {
  return (
    <div className="cs-fade" style={{ padding: 15, paddingTop: 55 }}>
      <div className="flex" style={{ gap: 15, marginBottom: 15 }}>
        <div className="cs-shimmer" style={{ width: 100, height: 140, borderRadius: 10 }} />
        <div className="flex-1 pt-1">
          <div className="cs-shimmer" style={{ height: 22, width: "85%", marginBottom: 12 }} />
          <div className="cs-shimmer" style={{ height: 14, width: "55%", marginBottom: 10 }} />
          <div className="cs-shimmer" style={{ height: 14, width: "40%", marginBottom: 18 }} />
          <div className="cs-shimmer" style={{ height: 44, width: 130, borderRadius: 8 }} />
        </div>
      </div>
      {Array.from({ length: 4 }).map((_, i) => (
        <div key={i} className="cs-shimmer" style={{ height: 50, borderRadius: 10, marginBottom: 8 }} />
      ))}
    </div>
  );
}

export function ResultScreen({
  item, source, onBack, onPlay,
}: {
  item: SearchItem;
  source: string;
  onBack: () => void;
  onPlay: (args: {
    detail: Detail;
    episodeIndex: number;
    resumeAt: number;
    episodes: Episode[];
  }) => void;
}) {
  const [detail, setDetail] = useState<Detail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [booked, setBooked] = useState(false);
  const [season, setSeason] = useState(1);

  useEffect(() => {
    let alive = true;
    (async () => {
      setDetail(null); setError(null); setBooked(isBookmarked(item.url));
      try {
        const d = await fetchDetail(source, item.url);
        if (alive) setDetail(d);
      } catch (e) {
        if (alive) setError(String(e));
      }
    })();
    // browse history → Supabase (silent when not configured)
    recordBrowse(source, item.title, item.url, item.poster);
    return () => { alive = false; };
  }, [source, item.url, item.title, item.poster]);

  const seasons = detail?.episodes
    ? [...new Set(detail.episodes.map((e) => e.season ?? 1))].sort()
    : [];

  const epsOfSeason = detail?.episodes?.filter((e) => (e.season ?? 1) === season) ?? [];

  const play = useCallback(async (episodeIndex: number) => {
    if (!detail) return;
    // CloudStream parity: inside this user gesture, go fullscreen + lock
    // landscape so the loading screen and playback are already 16:9.
    enterLandscapeNow();
    onPlay({ detail, episodeIndex, resumeAt: 0, episodes: detail.episodes ?? [] });
  }, [detail, onPlay]);

  if (error) {
    return (
      <div className="cs-fade flex flex-col items-center justify-center" style={{ paddingTop: "38vh", gap: 14 }}>
        <Icon d={P.warning} size={46} color="#9ba0a4" />
        <p style={{ color: "#e9eaee", fontSize: 14.5, textAlign: "center", padding: "0 40px" }}>
          Failed to load this title. {error}
        </p>
        <button onClick={onBack} className="cs-btn" style={{ background: "#e9eaee", color: "#1C1C20", fontWeight: 700, padding: "10px 24px", borderRadius: 8 }}>
          Go back
        </button>
      </div>
    );
  }

  if (!detail) return <ShimmerResult />;

  const imdb = detail.imdb && detail.imdb !== "x" ? detail.imdb : null;
  const metaParts = [
    detail.type === "TvSeries" ? "TV Series" : "Movie",
    detail.year ? String(detail.year) : null,
    imdb ? `Rated: ${imdb}/10.0` : null,
    detail.contentQuality || null,
    detail.language || null,
  ].filter(Boolean) as string[];

  const resumeInfo = null; // resume handled from player overlay via saved progress

  return (
    <div className="cs-fade" style={{ paddingBottom: 95 }}>
      {/* backdrop 200dp + bottom gradient */}
      <div className="relative" style={{ height: 210, overflow: "hidden" }}>
        {detail.poster && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={imgUrl(detail.poster)} alt="" className="absolute inset-0 w-full h-full object-cover" />
        )}
        <div className="absolute inset-0" style={{ background: "linear-gradient(to bottom, rgba(17,17,17,.25), rgba(17,17,17,.65) 70%, #111111)" }} />
        <button
          onClick={onBack}
          className="cs-btn absolute rounded-full flex items-center justify-center"
          style={{ top: 10, left: 4, width: 42, height: 42, background: "rgba(0,0,0,.4)", color: "#fff" }}
          aria-label="Back"
        >
          <Icon d={P.back} size={22} />
        </button>
        <button
          onClick={() => { setBooked(toggleBookmark({ title: detail.title, url: detail.url, poster: detail.poster, type: detail.type }, source)); }}
          className="cs-btn absolute rounded-full flex items-center justify-center"
          style={{ top: 10, right: 10, width: 42, height: 42, background: "rgba(0,0,0,.4)", color: booked ? "#3d50fa" : "#fff" }}
          aria-label="Bookmark"
        >
          <Icon d={booked ? P.bookmark : P.bookmarkOff} size={21} />
        </button>
      </div>

      <div style={{ padding: "0 15px" }}>
        {/* poster + title + meta */}
        <div className="flex" style={{ gap: 12, marginTop: -46, marginBottom: 15, position: "relative", zIndex: 2 }}>
          <div className="shrink-0" style={{ width: 100, height: 140, borderRadius: 10, overflow: "hidden", background: "#2B2C30", boxShadow: "0 8px 22px rgba(0,0,0,.55)" }}>
            {detail.poster && (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={imgUrl(detail.poster)} alt={detail.title} className="w-full h-full object-cover" />
            )}
          </div>
          <div className="flex-1 min-w-0" style={{ paddingTop: 50 }}>
            <h1 className="font-bold" style={{ color: "#e9eaee", fontSize: 19, lineHeight: 1.22, marginBottom: 6, display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}>
              {detail.title}
            </h1>
            <div className="flex flex-wrap items-center" style={{ gap: 8 }}>
              <span
                className="rounded-md font-bold"
                style={{ background: "#1C1C20", border: "1px solid #2B2C30", color: "#9ba0a4", fontSize: 10.5, padding: "3px 8px" }}
              >
                {sourceById(detail.source)?.name || detail.source}
              </span>
              {metaParts.map((m) => (
                <span key={m} style={{ color: "#9ba0a4", fontSize: 13 }}>{m}</span>
              ))}
            </div>
          </div>
        </div>

        {/* Play button — WhiteButton style */}
        <button
          onClick={() => play(0)}
          className="cs-btn flex items-center justify-center gap-2 font-bold"
          style={{ background: "#e9eaee", color: "#1C1C20", borderRadius: 6, height: 46, minWidth: 200, fontSize: 15, marginBottom: 14 }}
        >
          <Icon d={P.play} size={22} color="#1C1C20" />
          {detail.type === "TvSeries" && epsOfSeason.length > 0 ? "Play" : "Play"}
        </button>

        {/* description */}
        {detail.plot && (
          <div style={{ marginBottom: 12, position: "relative" }}>
            <p
              onClick={() => setExpanded(!expanded)}
              style={{
                color: "#e9eaee", fontSize: 14.5, lineHeight: 1.5, whiteSpace: "pre-line",
                cursor: "pointer",
                maxHeight: expanded ? "none" : 150,
                overflow: "hidden",
                WebkitMaskImage: expanded ? undefined : "linear-gradient(to bottom, #000 65%, transparent 100%)",
              }}
            >
              {detail.plot}
            </p>
            <button
              onClick={() => setExpanded(!expanded)}
              className="cs-btn mx-auto flex rounded-full"
              style={{ color: "#9ba0a6", padding: 4, marginTop: 2 }}
              aria-label={expanded ? "Collapse" : "Expand"}
            >
              <Icon d={expanded ? P.collapse : P.expand} size={22} />
            </button>
          </div>
        )}

        {/* tags */}
        {detail.tags && detail.tags.length > 0 && (
          <div className="flex flex-wrap" style={{ gap: 8, marginTop: 6, marginBottom: 14 }}>
            {detail.tags.map((t) => (
              <span key={t} className="rounded-full" style={{ background: "#1C1C20", border: "1px solid #2B2C30", color: "#9ba0a4", fontSize: 12, padding: "6px 13px" }}>
                {t}
              </span>
            ))}
          </div>
        )}

        {/* episodes */}
        {detail.type === "TvSeries" && detail.episodes && detail.episodes.length > 0 && (
          <div>
            <div className="flex items-center justify-between" style={{ margin: "8px 0 10px" }}>
              <h2 className="font-bold" style={{ fontSize: 16 }}>Episodes</h2>
              {seasons.length > 1 && (
                <select
                  value={season}
                  onChange={(e) => setSeason(Number(e.target.value))}
                  className="cs-btn"
                  style={{ background: "#1C1C20", color: "#e9eaee", border: "1px solid #2B2C30", borderRadius: 8, fontSize: 13, padding: "7px 10px", outline: "none" }}
                  aria-label="Season"
                >
                  {seasons.map((s) => <option key={s} value={s}>Season {s}</option>)}
                </select>
              )}
            </div>
            {epsOfSeason.map((ep) => {
              const idx = detail.episodes!.indexOf(ep);
              return (
                <EpisodeCard key={ep.data.slice(0, 40) + idx} ep={ep} index={idx} onPlay={() => play(idx)} />
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

function EpisodeCard({ ep, index, onPlay }: { ep: Episode; index: number; onPlay: () => void }) {
  return (
    <button
      onClick={onPlay}
      className="cs-btn w-full flex items-center text-left hover:bg-white/[.04] rounded-[10px]"
      style={{ height: 50, marginBottom: 5, background: "transparent", padding: "0 10px" }}
      aria-label={ep.name}
    >
      <span
        className="shrink-0 flex items-center justify-center rounded-full"
        style={{ width: 40, height: 40, background: "#1C1C20", color: "#e9eaee", marginRight: 10 }}
      >
        <Icon d={P.play} size={19} />
      </span>
      <span className="flex-1 min-w-0">
        <span className="block truncate" style={{ color: "#e9eaee", fontSize: 14 }}>{ep.name}</span>
        {ep.qualities && (
          <span className="block" style={{ color: "#9ba0a4", fontSize: 11.5 }}>Quality: {ep.qualities}</span>
        )}
      </span>
      <Icon d={P.chevR} size={20} color="#9ba0a6" />
    </button>
  );
}
