// Provider registry — the web equivalent of CloudStream's MainAPI list.
// NOTE: banglaplex + fojik were REMOVED from the web app per user decision:
// - banglaplex: all 3 servers are protected (abyss ad-gate + 2 SPA players
//   with 404-guarded bundles) — nothing playable server-side
// - fojik: extraction chain works locally but Cloudflare Workers egress IPs
//   are host-blocked (error 1020) → dead on production
// Provider files stay for reference; the CloudStream plugins still ship.

import type { HomeRow, SearchItem, Detail, StreamLink } from "./types";
import { MLSBD_CATEGORIES, mlsbdHome, mlsbdSearch, mlsbdLoad, mlsbdLoadLinks } from "./mlsbd";
import { SOUTHFREAK_CATEGORIES, southfreakHome, southfreakSearch, southfreakLoad, southfreakLoadLinks } from "./southfreak";
import { MOVIELINKBD_CATEGORIES, movielinkbdHome, movielinkbdSearch, movielinkbdLoad, movielinkbdLoadLinks } from "./movielinkbd";

export interface SourceDef {
  id: string;
  name: string;
  lang: string;
  color: string;       // avatar color
  short: string;       // avatar letter(s)
  categories: [string, string][];
  home: (category: string, page: number) => Promise<HomeRow>;
  search: (q: string, page?: number) => Promise<SearchItem[]>;
  load: (url: string) => Promise<Detail>;
  loadLinks: (data: string) => Promise<StreamLink[]>;
}

export const SOURCES: SourceDef[] = [
  {
    id: "mlsbd",
    name: "MLSBD",
    lang: "bn",
    color: "#F53B66",
    short: "M",
    categories: MLSBD_CATEGORIES,
    home: mlsbdHome,
    search: (q) => mlsbdSearch(q),
    load: mlsbdLoad,
    loadLinks: mlsbdLoadLinks,
  },
  {
    id: "southfreak",
    name: "SouthFreak",
    lang: "hi",
    color: "#E4D448",
    short: "S",
    categories: SOUTHFREAK_CATEGORIES,
    home: southfreakHome,
    search: southfreakSearch,
    load: southfreakLoad,
    loadLinks: southfreakLoadLinks,
  },
  {
    id: "movielinkbd",
    name: "MovieLinkBD",
    lang: "bn",
    color: "#3D50FA",
    short: "ML",
    categories: MOVIELINKBD_CATEGORIES,
    home: movielinkbdHome,
    search: (q) => movielinkbdSearch(q),
    load: movielinkbdLoad,
    loadLinks: movielinkbdLoadLinks,
  },
];

export function sourceById(id: string): SourceDef | undefined {
  return SOURCES.find((s) => s.id === id);
}
