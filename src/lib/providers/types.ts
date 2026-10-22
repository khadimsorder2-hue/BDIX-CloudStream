// CloudStream data-model equivalents (subset the web app needs).

export type TvType = "Movie" | "TvSeries" | "AsianDrama" | "AnimeMovie" | "Others";

export interface SearchItem {
  title: string;
  url: string;
  poster: string | null;
  type: TvType;
  quality?: string | null;
}

export interface HomeRow {
  title: string;
  items: SearchItem[];
  hasNext: boolean;
}

export interface Episode {
  /** opaque data payload passed to /api/links */
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
  type: TvType;
  /** movie: packed data string; series: null */
  movieData: string | null;
  episodes: Episode[] | null;
  imdb?: string | null;
  language?: string | null;
  contentQuality?: string | null;
  size?: string | null;
}

export interface StreamLink {
  name: string;      // source name (MLSBD / SouthFreak)
  label: string;     // "720p GDFlix R2" etc
  url: string;
  quality: number;   // 0 unknown
  referer: string;
  /** true when the URL is a page (not direct bytes) and must be opened via /api/proxy page or browser */
  isPage?: boolean;
}
