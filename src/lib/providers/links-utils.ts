// StreamLink post-processing shared by the Next.js API routes and the
// Cloudflare worker bundle. Fixes the "Source error" bug: host *pages*
// (GDXShare/HubCloud/FileHost/1Fichier landing pages) were being served as
// playable links, so <video> received text/html and errored instantly.
// classify: mark them isPage, then sort best-quality direct streams first.

import type { StreamLink } from "./types";

const DIRECT_URL_RE =
  /(\.(mp4|mkv|m3u8|webm|ts|m4v|avi|mov|mpd)(\?|$)|\/\/pub-[a-z0-9-]+\.r2\.dev|multidownload\.lol|fastcdn-dl\.pages\.dev|busycdn|pixeldrain\.com\/api\/file|action=(watch|download)|\.r2\.cloudflarestorage\.com|indexserver|workers\.dev\/(dl|stream)|dl\.vircloud\.site\/download)/i;

const PAGE_URL_RE =
  /(gdxshare\.com|hubcloud\.|hubdrive\.|gdflix|gdxfiles\.com|filebee\.|1fichier\.com|savelinks\.me|techzed\.info|multicloudlinks|gofile\.io|filepress|dropgalaxy|krakenfiles|terabox|dropbox\.com\/s|mega\.nz|medi fire)/i;

export function looksLikePage(l: StreamLink): boolean {
  if (l.isPage) return true;
  if (DIRECT_URL_RE.test(l.url)) return false;
  if (PAGE_URL_RE.test(l.url)) return true;
  // unknown host with no parsed quality: treat as a page so the player
  // never starts on it (still selectable/failover-able at the end)
  return !l.quality;
}

export function organizeLinks(links: StreamLink[]): StreamLink[] {
  const out: StreamLink[] = [];
  for (const l of links) {
    const page = looksLikePage(l);
    out.push(page ? { ...l, isPage: true } : { ...l, isPage: false });
  }
  const score = (l: StreamLink) => {
    const p = l.isPage ? 1 : 0;
    return p * 100000 + (10000 - (l.quality || 0));
  };
  return out.sort((a, b) => score(a) - score(b));
}
