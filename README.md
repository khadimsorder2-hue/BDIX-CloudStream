# SkStream — Web Version (webapp-src branch)

> **Note:** Ei branch ta shudhu web version er **source code backup**.
> Main extension repo `main` branch e ache — install URL:
> `https://raw.githubusercontent.com/khadimsorder2-hue/BDIX-CloudStream/main/repo.json`

## Ei web app ki?
SkStream = CloudStream app er **pixel-to-pixel web copy** — MLSBD + SouthFreak source soho:
- **Homepage**: per-source horizontal poster rows, Continue Watching, bottom nav (Home/Search/Library/Downloads/Settings)
- **Floating Source Selector**: FAB button -> Sources bottom sheet (All / MLSBD / SouthFreak)
- **Detail page**: backdrop + poster + Play button + IMDb rating + plot (SkStream ResultScreen (CloudStream pixel-copy))
- **Player**: CloudStream ExoPlayer pixel copy — Skip Intro +85s, ±10s double-tap,
  swipe seek bubble, brightness/volume swipe, long-press 2x, lock, speed/quality/subtitle/audio/episode menu
- **Standalone player module**: `public/player/` folder — kono framework chhara nijerai chole
  (`player.js` + `player.css` + `index.html`), window.SkStreamPlayer.mount() API

## Chalate (run):
```bash
bun install
bun run dev        # http://localhost:3000
```

## API routes:
- `/api/home?source=mlsbd|southfreak&category=&page=1`
- `/api/search?source=all&q=...`
- `/api/detail?source=...&url=...`
- `/api/links?source=...&data=...`  (savelinks -> multicloud -> direct link resolver)
- `/api/proxy?u=...` (UA-伪装 + Range passthrough — UA-blocked CDN er jonno)
- `/api/img?u=...` (poster proxy)

## Standalone player (alada):
`public/player/` — file gulo nijer server e rakha jay, iframe/direct link diye use kora jay.
