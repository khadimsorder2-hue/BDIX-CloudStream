/* BDIX CloudStream Web — SPA (CloudStream app UI clone)
   Views: home / search / detail / player. Hash routing. */
"use strict";

// ---------------------------------------------------------------- helpers
const $app = document.getElementById("app");
const $playerRoot = document.getElementById("player-root");

const esc = (s) => String(s == null ? "" : s)
  .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
  .replace(/"/g, "&quot;").replace(/'/g, "&#39;");

function toast(msg, ms = 2600) {
  const root = document.getElementById("toast-root");
  const el = document.createElement("div");
  el.className = "toast";
  el.textContent = msg;
  root.appendChild(el);
  setTimeout(() => el.remove(), ms);
}

const ICONS = {
  search: '<svg width="23" height="23" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.8-3.8"/></svg>',
  back: '<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>',
  play: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>',
  pause: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>',
  replay10: '<svg width="34" height="34" viewBox="0 0 24 24" fill="currentColor"><path d="M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z"/><text x="12" y="15.5" text-anchor="middle" font-size="7.5" font-weight="bold" fill="currentColor" font-family="Roboto,sans-serif">10</text></svg>',
  fwd10: '<svg width="34" height="34" viewBox="0 0 24 24" fill="currentColor"><path d="M12 5V1l5 5-5 5V7c-3.31 0-6 2.69-6 6s2.69 6 6 6 6-2.69 6-6h2c0 4.42-3.58 8-8 8s-8-3.58-8-8 3.58-8 8-8z"/><text x="12" y="15.5" text-anchor="middle" font-size="7.5" font-weight="bold" fill="currentColor" font-family="Roboto,sans-serif">10</text></svg>',
  fullscreen: '<svg width="21" height="21" viewBox="0 0 24 24" fill="currentColor"><path d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"/></svg>',
  fullscreenExit: '<svg width="21" height="21" viewBox="0 0 24 24" fill="currentColor"><path d="M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z"/></svg>',
  quality: '<svg width="21" height="21" viewBox="0 0 24 24" fill="currentColor"><path d="M19.43 12.98c.04-.32.07-.64.07-.98s-.03-.66-.07-.98l2.11-1.65a.5.5 0 0 0 .12-.64l-2-3.46a.5.5 0 0 0-.61-.22l-2.49 1a7.03 7.03 0 0 0-1.69-.99l-.38-2.65A.49.49 0 0 0 14 2h-4a.49.49 0 0 0-.49.42l-.38 2.65c-.61.25-1.17.59-1.69.99l-2.49-1a.5.5 0 0 0-.61.22l-2 3.46a.5.5 0 0 0 .12.64l2.11 1.65c-.04.32-.07.65-.07.98s.03.66.07.98l-2.11 1.65a.5.5 0 0 0-.12.64l2 3.46c.12.22.39.3.61.22l2.49-1c.52.4 1.08.74 1.69.99l.38 2.65c.04.24.24.42.49.42h4c.25 0 .46-.18.49-.42l.38-2.65a6.87 6.87 0 0 0 1.69-.99l2.49 1c.23.09.49 0 .61-.22l2-3.46a.5.5 0 0 0-.12-.64l-2.11-1.65zM12 15.5A3.5 3.5 0 1 1 12 8.5a3.5 3.5 0 0 1 0 7z"/></svg>',
  speed: '<svg width="21" height="21" viewBox="0 0 24 24" fill="currentColor"><path d="M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z"/></svg>',
  epList: '<svg width="21" height="21" viewBox="0 0 24 24" fill="currentColor"><path d="M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z"/></svg>',
  arrowFwd: '<svg class="arrow" viewBox="0 0 24 24" fill="#fff"><path d="M8.59 16.59 13.17 12 8.59 7.41 10 6l6 6-6 6z"/></svg>',
  external: '<svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><path d="M19 19H5V5h7V3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z"/></svg>',
  info: '<svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><path d="M11 17h2v-6h-2v6zm1-15C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zM11 9h2V7h-2v2z"/></svg>',
};

// ---------------------------------------------------------------- storage
const LS = {
  get(k, d) { try { return JSON.parse(localStorage.getItem("bdix:" + k)) ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem("bdix:" + k, JSON.stringify(v)); } catch {} },
};
function saveProgress(entry) {
  // entry: {source,url,name,poster,ep,quality,link,position,duration,ts}
  const list = LS.get("progress", []);
  const i = list.findIndex((x) => x.url === entry.url && x.link === entry.link && x.ep === entry.ep);
  if (i >= 0) list.splice(i, 1);
  entry.ts = Date.now();
  list.unshift(entry);
  LS.set("progress", list.slice(0, 30));
}

// ---------------------------------------------------------------- API
async function api(path) {
  const res = await fetch("/api/" + path);
  if (!res.ok) {
    let msg = res.status + " " + res.statusText;
    try { msg = (await res.json()).error || msg; } catch {}
    throw new Error(msg);
  }
  return res.json();
}
let SOURCES = [];
async function loadSources() {
  if (SOURCES.length) return SOURCES;
  const data = await api("sources");
  SOURCES = data.sources;
  return SOURCES;
}

// ---------------------------------------------------------------- router
function navigate(hash) { location.hash = hash; }
window.addEventListener("hashchange", route);

async function route() {
  const hash = location.hash || "#/";
  const body = hash.replace(/^#\/?/, "");
  const [pathPart, queryPart] = body.split("?");
  const [view, ...rest] = pathPart.split("/");
  const qs = new URLSearchParams(queryPart || "");
  window.scrollTo(0, 0);
  if (view === "" || view === "home") return viewHome();
  if (view === "search") return viewSearch(decodeURIComponent(qs.get("q") || ""));
  if (view === "detail") {
    return viewDetail(qs.get("s") || "mlsbd", qs.get("url") || "");
  }
  viewHome().catch((e) => fatal(e));
}

function fatal(e) {
  $app.innerHTML = `<div class="err-card">⚠ ${esc(e.message || e)}<br><br><button class="load-more" onclick="location.reload()">Reload</button></div>`;
}

// ---------------------------------------------------------------- HOME
let homeState = { source: "all", cats: {}, loaded: {} };

async function viewHome() {
  const sources = await loadSources();
  const sel = homeState.source;
  const shown = sel === "all" ? sources : sources.filter((s) => s.id === sel);
  const progress = LS.get("progress", []);

  let html = `
  <div class="home-header">
    <div class="home-topbar">
      <div class="search-trigger" id="search-trigger">${ICONS.search}<span style="font-size:14.5px;opacity:.55">Search…</span></div>
      <div class="avatar" title="BDIX CloudStream"><span>▶</span></div>
    </div>
    <div class="source-chips" id="source-chips">
      <div class="chip ${sel === "all" ? "active" : ""}" data-src="all">All</div>
      ${sources.map((s) => `<div class="chip ${sel === s.id ? "active" : ""}" data-src="${s.id}">${s.icon} ${esc(s.name)}</div>`).join("")}
    </div>
  </div>
  <div id="home-rows"></div>`;

  if (progress.length) {
    html = html.replace('<div id="home-rows"></div>', `
      <div class="row" id="continue-row">
        <div class="row-header"><span>Continue Watching</span>${ICONS.arrowFwd}</div>
        <div class="row-scroll">
          ${progress.slice(0, 14).map((p) => `
            <div class="card" data-cw='${esc(JSON.stringify(p))}'>
              ${p.poster ? `<img loading="lazy" src="${esc(p.poster)}" onerror="this.style.opacity=.15">` : `<img src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='114' height='180'%3E%3Crect width='114' height='180' fill='%231C1C20'/%3E%3C/svg%3E">`}
              <div class="shadow"></div>
              <div class="img-text">${esc(p.name)}${p.ep ? " · " + esc(p.ep) : ""}</div>
            </div>`).join("")}
        </div>
      </div>
      <div id="home-rows"></div>`);
  }

  $app.innerHTML = html;
  document.getElementById("search-trigger").onclick = () => navigate("#/search");
  document.getElementById("source-chips").onclick = (e) => {
    const chip = e.target.closest(".chip");
    if (!chip) return;
    homeState.source = chip.dataset.src;
    viewHome();
  };
  const contRow = document.getElementById("continue-row");
  if (contRow) {
    contRow.onclick = (e) => {
      const card = e.target.closest(".card[data-cw]");
      if (!card) return;
      const p = JSON.parse(card.dataset.cw);
      openWatch(p);
    };
  }

  const rowsEl = document.getElementById("home-rows");
  // CloudStream home: interleaved rows from every enabled provider
  const perSource = shown.flatMap((s) =>
    s.categories.slice(0, 5).map(([k, label]) => ({ src: s, cat: k, label }))
  );
  for (const job of perSource) {
    const row = document.createElement("div");
    row.className = "row";
    row.innerHTML = `<div class="row-header"><span>${esc(job.label)}</span><span style="font-size:11.5px;color:var(--grayTextColor);margin-left:8px">${esc(job.src.name)}</span>${ICONS.arrowFwd}</div>
      <div class="row-scroll"><div class="loading-box" style="margin:0 auto"><div class="spinner" style="margin:14px auto"></div></div></div>`;
    rowsEl.appendChild(row);
    const scroll = row.querySelector(".row-scroll");
    api(`${job.src.id}/home?cat=${encodeURIComponent(job.cat)}&page=1`)
      .then((data) => {
        if (!data.items || !data.items.length) { row.remove(); return; }
        scroll.innerHTML = data.items.map((it) => cardHtml(job.src.id, it)).join("");
        bindCards(scroll, job.src.id);
      })
      .catch(() => row.remove());
  }
}

function cardHtml(sourceId, it) {
  const isHevc = /hevc/i.test(it.name || "");
  const q = qualityOf(it.name);
  return `<div class="card" data-src-id="${sourceId}" data-url="${esc(it.url)}" data-name="${esc(it.name)}" data-poster="${esc(it.poster || "")}" data-type="${esc(it.type)}">
    ${it.poster ? `<img loading="lazy" src="${esc(it.poster)}" onerror="this.style.opacity=.15">` : `<img src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='114' height='180'%3E%3Crect width='114' height='180' fill='%231C1C20'/%3E%3C/svg%3E">`}
    <div class="shadow"></div>
    <div class="img-text">${esc(it.name)}</div>
    <div class="badges">
      ${q ? `<span class="badge quality">${esc(q)}</span>` : ""}
      ${isHevc ? `<span class="badge hevc">HEVC</span>` : ""}
    </div>
  </div>`;
}

function qualityOf(name) {
  const m = String(name).match(/\b(2160|1440|1080|720|480|360)p\b|4K/i);
  if (!m) return null;
  return m[0].toUpperCase() === "4K" ? "4K" : m[0];
}

function bindCards(scope, sourceId) {
  scope.querySelectorAll(".card[data-url]").forEach((card) => {
    card.onclick = () => {
      navigate(`#/detail?s=${sourceId}&url=${encodeURIComponent(card.dataset.url)}`);
    };
  });
}

// ---------------------------------------------------------------- SEARCH
function viewSearch(prefill) {
  $app.innerHTML = `
  <div class="search-page">
    <div class="search-bar">
      <button class="icon-btt" id="search-back">${ICONS.back}</button>
      <input id="search-input" type="search" placeholder="Search…" autocomplete="off" value="${esc(prefill || "")}">
    </div>
    <div class="search-results" id="search-results">
      <div class="empty-box">Type to search MLSBD &amp; SouthFreak</div>
    </div>
  </div>`;
  document.getElementById("search-back").onclick = () => history.back();
  const input = document.getElementById("search-input");
  const results = document.getElementById("search-results");
  input.focus();
  let deb = null;
  let seq = 0;
  input.oninput = () => {
    clearTimeout(deb);
    const q = input.value.trim();
    if (q.length < 2) { results.innerHTML = `<div class="empty-box">Type to search…</div>`; return; }
    deb = setTimeout(async () => {
      const mySeq = ++seq;
      results.innerHTML = `<div class="loading-box"><div class="spinner"></div>Searching…</div>`;
      try {
        const sources = await loadSources();
        const lists = await Promise.all(sources.map((s) => api(`${s.id}/search?q=${encodeURIComponent(q)}`).catch(() => ({ items: [] }))));
        if (mySeq !== seq) return;
        let any = false;
        results.innerHTML = lists.flatMap((l, i) =>
          (l.items || []).map((it) => { any = true; return searchCardHtml(sources[i].id, it); })
        ).join("") || `<div class="empty-box">No results for "${esc(q)}"</div>`;
        bindCards(results, null);
      } catch (e) {
        results.innerHTML = `<div class="err-card">⚠ ${esc(e.message)}</div>`;
      }
    }, 420);
  };
  if (prefill) input.dispatchEvent(new Event("input"));
}

function searchCardHtml(sourceId, it) {
  const html = cardHtml(sourceId, it);
  return html.replace('class="card"', 'class="card"');
}

// ---------------------------------------------------------------- DETAIL
async function viewDetail(sourceId, url) {
  $app.innerHTML = `
    <button class="icon-btt top-back" id="detail-back">${ICONS.back}</button>
    <div class="detail">
      <div class="detail-banner">
        <div class="fade"></div>
      </div>
      <div class="loading-box"><div class="spinner"></div>Loading…</div>
    </div>`;
  document.getElementById("detail-back").onclick = () => history.back();
  try {
    const d = await api(`${sourceId}/detail?url=${encodeURIComponent(url)}`);
    renderDetail(sourceId, d);
  } catch (e) {
    $app.querySelector(".loading-box").outerHTML = `<div class="err-card">⚠ ${esc(e.message)}</div>`;
  }
}

function renderDetail(sourceId, d) {
  const isSeries = d.type === "TvSeries" && Array.isArray(d.episodes);
  const banner = $app.querySelector(".detail-banner");
  banner.innerHTML = `
    ${d.poster ? `<img class="bg" src="${esc(d.poster)}" onerror="this.remove()">` : ""}
    <div class="fade"></div>
    <div class="detail-head">
      ${d.poster ? `<img class="poster" src="${esc(d.poster)}" onerror="this.remove()">` : ""}
      <div class="titles">
        <h1>${esc(d.title)}</h1>
        <div class="detail-badges">
          ${d.year ? `<span class="badge quality">${d.year}</span>` : ""}
          <span class="badge quality">${isSeries ? "TV" : "Movie"}</span>
          ${isSeries ? `<span class="badge dub">${d.episodes.length} EP</span>` : ""}
        </div>
      </div>
    </div>`;
  const tags = [...(d.tags || [])];
  const plot = d.plot || "";
  const body = document.createElement("div");
  body.className = "detail-body";
  body.innerHTML = `
    ${tags.length ? `<div class="detail-tags">${tags.map((t) => `<span>${esc(t)}</span>`).join("")}</div>` : ""}
    ${plot ? `<p class="detail-plot">${esc(plot)}</p>` : ""}
    ${isSeries ? `<div class="page-title" style="padding-left:0">Episodes</div><div class="ep-list" id="ep-list"></div>` : ""}
  `;
  banner.after(body);

  const playFab = document.createElement("button");
  playFab.className = "play-fab";
  playFab.innerHTML = `${ICONS.play} Play`;
  document.body.appendChild(playFab);

  const openLinks = async (epIndex) => {
    let data;
    if (isSeries) data = d.episodes[epIndex].data;
    else data = d.data;
    playFab.innerHTML = `${ICONS.play} Loading…`;
    try {
      const res = await api(`${sourceId}/links?data=${encodeURIComponent(data)}`);
      playFab.innerHTML = `${ICONS.play} Play`;
      if (!res.links || !res.links.length) return toast("No links found 😕");
      showSourceSheet(d, res.links, { sourceId, url: d.url, name: d.title, poster: d.poster, ep: isSeries ? d.episodes[epIndex].name : null });
    } catch (e) {
      playFab.innerHTML = `${ICONS.play} Play`;
      toast("Link error: " + e.message);
    }
  };

  playFab.onclick = () => openLinks(isSeries ? 0 : null);

  if (isSeries) {
    const epList = document.getElementById("ep-list");
    epList.innerHTML = d.episodes.map((ep, i) => {
      const num = ep.episode || i + 1;
      return `<div class="ep-item" data-ep="${i}">
        <div class="num">${num}</div>
        <div class="meta">
          <div class="t">${esc(ep.name)}</div>
        </div>
        <span class="go">${ICONS.play.replace('<svg', '<svg width="18" height="18"')}</span>
      </div>`;
    }).join("");
    epList.onclick = (e) => {
      const item = e.target.closest(".ep-item");
      if (!item) return;
      openLinks(+item.dataset.ep);
    };
  }
}

// ---------------------------------------------------------------- sheet
function showSourceSheet(d, links, ctx) {
  const bd = document.createElement("div");
  bd.className = "sheet-backdrop";
  bd.innerHTML = `
    <div class="sheet">
      <h3>Sources · ${esc(d.title)}</h3>
      <div class="links">
        ${links.map((l, i) => `
          <button class="link-item" data-i="${i}">
            <span class="q">${l.quality ? esc(l.quality + "p") : "HD"}</span>
            <span class="lbl">${esc(l.label)}</span>
            ${l.direct ? "" : `<span class="external">page ${ICONS.external}</span>`}
          </button>`).join("")}
      </div>
    </div>`;
  bd.onclick = (e) => { if (e.target === bd) bd.remove(); };
  bd.querySelector(".links").onclick = (e) => {
    const btn = e.target.closest(".link-item");
    if (!btn) return;
    const l = links[+btn.dataset.i];
    bd.remove();
    openWatch({ ...ctx, quality: l.quality, linkLabel: l.label, link: l.url, referer: l.referer, direct: l.direct });
  };
  document.body.appendChild(bd);
}

// ---------------------------------------------------------------- WATCH entry
function openWatch(ctx) {
  // ctx: {sourceId,url,name,poster,ep,quality,link,linkLabel,referer,direct}
  sessionStorage.setItem("bdix:watch", JSON.stringify(ctx));
  window.__openPlayer(ctx);
}

// start
route();
