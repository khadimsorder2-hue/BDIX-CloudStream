// Shared client store: SkStream data types, API helpers, localStorage
// (progress / bookmarks / preferred source), and history-based navigation.

import { useCallback, useEffect, useState } from "react";

export interface SearchItem {
  title: string;
  url: string;
  poster: string | null;
  type: string;
}

export interface HomeRowData {
  title: string;
  items: SearchItem[];
  hasNext: boolean;
  category: string;
  page: number;
  source: string;
}

export interface Episode {
  data: string;
  name: string;
  season: number | null;
  episode: number | null;
  qualities?: string;
}

export interface Detail {
  source: string;
  title: string;
  url: string;
  poster: string | null;
  plot: string | null;
  tags: string[] | null;
  year: number | null;
  type: string;
  movieData: string | null;
  episodes: Episode[] | null;
  imdb?: string | null;
  language?: string | null;
  contentQuality?: string | null;
  size?: string | null;
}

export interface StreamLink {
  name: string;
  label: string;
  url: string;
  quality: number;
  referer: string;
  isPage?: boolean;
}

export interface SourceDef {
  id: string;
  name: string;
  lang: string;
  color: string;
  short: string;
  categories: [string, string][];
}

export const SOURCES: SourceDef[] = [
  {
    id: "mlsbd",
    name: "MLSBD",
    lang: "bn",
    color: "#F53B66",
    short: "M",
    categories: [
      ["", "Latest Movies"],
      ["category/bangla-dubbed", "Bangla Dubbed"],
      ["category/dual-audio-movies", "Multi Audio Movies"],
      ["category/tv-series", "TV Series"],
      ["category/bollywood-movies", "Bollywood Movies"],
      ["category/bangla-movies", "Bengali Movies"],
      ["category/hollywood-movies", "Hollywood Movies"],
    ],
  },
  {
    id: "southfreak",
    name: "SouthFreak",
    lang: "hi",
    color: "#E4D448",
    short: "S",
    categories: [
      ["latest", "Latest"],
      ["7", "Hindi Dubbed"],
      ["6", "Dual Audio"],
      ["19", "Bengali"],
      ["8", "Hollywood"],
      ["2", "WEB/TV Series"],
      ["26", "Korean Movie & Drama"],
      ["3", "Action"],
      ["9", "Drama"],
      ["5", "Comedy"],
      ["16", "Crime"],
      ["17", "Thriller"],
    ],
  },
  {
    id: "movielinkbd",
    name: "MovieLinkBD",
    lang: "bn",
    color: "#3D50FA",
    short: "ML",
    categories: [
      ["", "Latest"],
      ["bangla", "Bangla Movies"],
      ["bangla-dubbed", "Bangla Dubbed"],
      ["hindi", "Hindi Movies"],
      ["hindi-dubbed", "Hindi Dubbed"],
      ["english-movies", "English Movies"],
      ["dual-audio", "Dual Audio"],
      ["web-series", "Web Series"],
    ],
  },
  // NOTE: banglaplex + fojik removed from web sources (unfixable on
  // production — see src/lib/providers/index.ts). CloudStream plugins remain.
];

export function sourceById(id: string): SourceDef | undefined {
  return SOURCES.find((s) => s.id === id);
}

// ------------------------------------------------------------------- api

export async function fetchHome(source: string, category: string, page = 1): Promise<HomeRowData> {
  const r = await fetch(`/api/home?source=${source}&category=${encodeURIComponent(category)}&page=${page}`);
  return r.json();
}

export async function fetchSearch(q: string, source: string): Promise<{ groups: { source: string; name: string; color: string; items: SearchItem[] }[] }> {
  const r = await fetch(`/api/search?q=${encodeURIComponent(q)}&source=${encodeURIComponent(source)}`);
  return r.json();
}

export async function fetchDetail(source: string, url: string): Promise<Detail> {
  const r = await fetch(`/api/detail?source=${source}&url=${encodeURIComponent(url)}`);
  if (!r.ok) throw new Error("detail failed: " + r.status);
  return r.json();
}

export async function fetchLinks(source: string, data: string, title?: string, refresh?: boolean): Promise<StreamLink[]> {
  const t = title ? `&title=${encodeURIComponent(title.slice(0, 160))}` : "";
  const r = refresh
    ? `&refresh=1` : "";
  const res = await fetch(`/api/links?source=${source}&data=${encodeURIComponent(data)}${t}${r}`);
  const j = await res.json();
  return j.links || [];
}

export function imgUrl(u: string | null | undefined): string {
  if (!u) return "";
  return `/api/img?u=${encodeURIComponent(u)}`;
}

// --------------------------------------------------------- localStorage

export interface ProgressEntry {
  url: string;
  source: string;
  title: string;
  poster: string | null;
  position: number;
  duration: number;
  episodeIndex: number;
  episodeName: string;
  updatedAt: number;
}

const K_PROGRESS = "cs_progress_v1";
const K_BOOKMARKS = "cs_bookmarks_v1";
const K_SOURCE = "cs_source_pref";

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}
function writeJson(key: string, v: unknown) {
  try { localStorage.setItem(key, JSON.stringify(v)); } catch { /* quota */ }
}

export function getProgressMap(): Record<string, ProgressEntry> {
  return readJson<Record<string, ProgressEntry>>(K_PROGRESS, {});
}
export function saveProgress(entry: ProgressEntry) {
  const map = getProgressMap();
  entry.updatedAt = Date.now();
  map[entry.url] = entry;
  // keep the 60 most recent
  const sorted = Object.values(map).sort((a, b) => b.updatedAt - a.updatedAt);
  const trimmed = Object.fromEntries(sorted.slice(0, 60).map((e) => [e.url, e]));
  writeJson(K_PROGRESS, trimmed);
  recordWatch(entry); // mirror to Supabase (silent no-op before setup)
}
export function getBookmarks(): SearchItem[] {
  return readJson<SearchItem[]>(K_BOOKMARKS, []);
}
export function toggleBookmark(item: SearchItem, source?: string): boolean {
  const list = getBookmarks();
  const idx = list.findIndex((b) => b.url === item.url);
  let saved: boolean;
  if (idx >= 0) { list.splice(idx, 1); saved = false; }
  else { list.unshift({ ...item, type: item.type || "Movie" }); saved = true; }
  writeJson(K_BOOKMARKS, list.slice(0, 100));
  // mirror to Supabase favourites when the caller knows the source
  if (source) toggleFavoriteRemote(source, item);
  return saved;
}
export function isBookmarked(url: string): boolean {
  return getBookmarks().some((b) => b.url === url);
}

export function getPreferredSource(): string {
  return localStorage.getItem(K_SOURCE) || "all";
}
export function setPreferredSource(id: string) {
  localStorage.setItem(K_SOURCE, id);
}

// ------------------------------------------------------------ react glue

export function useLocalListener(event: string, onChange: () => void) {
  useEffect(() => {
    window.addEventListener(event, onChange);
    return () => window.removeEventListener(event, onChange);
  }, [event, onChange]);
}

export function useSourcePreference() {
  const [src, setSrc] = useState("all");
  useEffect(() => {
    const sync = () => setSrc(getPreferredSource());
    const id = requestAnimationFrame(sync);
    window.addEventListener("cs-source-change", sync);
    return () => {
      cancelAnimationFrame(id);
      window.removeEventListener("cs-source-change", sync);
    };
  }, []);
  const apply = useCallback((id: string) => {
    setPreferredSource(id);
    setSrc(id);
    window.dispatchEvent(new Event("cs-source-change"));
  }, []);
  return { source: src, setSource: apply };
}

// ------------------------------------------------------------- helpers

export function fmtTime(t: number): string {
  if (!isFinite(t) || t < 0) t = 0;
  const s = Math.floor(t % 60);
  const m = Math.floor((t / 60) % 60);
  const h = Math.floor(t / 3600);
  const ss = String(s).padStart(2, "0");
  const mm = String(m).padStart(2, "0");
  return h > 0 ? `${h}:${mm}:${ss}` : `${m}:${ss}`;
}

// ------------------------------------------------- supabase recording glue
// Remote history/favourites recording (fire-and-forget; silent when the
// Supabase env is not configured yet).

function postJSON(path: string, body: unknown): void {
  try {
    fetch(path, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
      keepalive: true,
    }).catch(() => {});
  } catch { /* ignore */ }
}

export function recordWatch(entry: ProgressEntry): void {
  postJSON("/api/history", {
    kind: "watch",
    source: entry.source,
    title: entry.title,
    poster: entry.poster,
    url: entry.url,
    episodeIndex: entry.episodeIndex,
    episodeName: entry.episodeName,
    position: entry.position,
    duration: entry.duration,
  });
}

export function recordBrowse(source: string, title: string, url: string, poster: string | null): void {
  postJSON("/api/history", { kind: "browse", source, title, url, poster });
}

export function recordSearch(query: string): void {
  postJSON("/api/history", { kind: "search", query });
}

export function toggleFavoriteRemote(source: string, item: SearchItem): Promise<boolean | null> {
  return fetch("/api/favorites", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ source, title: item.title, poster: item.poster, url: item.url }),
  })
    .then((r) => r.json())
    .then((j: { saved?: boolean }) => (typeof j.saved === "boolean" ? j.saved : null))
    .catch(() => null);
}

export interface RemoteFav {
  id: number;
  source: string;
  title: string;
  poster: string | null;
  detail_url: string;
  created_at: string;
}

export async function fetchRemoteFavorites(): Promise<RemoteFav[]> {
  try {
    const r = await fetch("/api/favorites");
    const j = await r.json();
    return Array.isArray(j.items) ? j.items : [];
  } catch {
    return [];
  }
}

export interface RemoteWatch {
  id: number;
  source: string;
  title: string;
  poster: string | null;
  detail_url: string;
  episode_index: number;
  episode_name: string | null;
  position_seconds: number;
  duration_seconds: number;
  updated_at: string;
}

export async function fetchRemoteHistory(kind: "watch" | "browse" | "search", limit = 60): Promise<unknown[]> {
  try {
    const r = await fetch(`/api/history?kind=${kind}&limit=${limit}`);
    const j = await r.json();
    return Array.isArray(j.items) ? j.items : [];
  } catch {
    return [];
  }
}

export async function clearRemoteHistory(kind: "watch" | "browse" | "search"): Promise<void> {
  try { await fetch(`/api/history?kind=${kind}`, { method: "DELETE" }); } catch { /* ignore */ }
}
