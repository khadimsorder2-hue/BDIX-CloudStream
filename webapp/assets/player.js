/* BDIX CloudStream Web — Player (fragment_player.xml / player_custom_layout.xml clone)
   Center: replay10 / play-pause / forward10. Top: back + title + info.
   Bottom: times, seekbar (played/buffer/knob), quality·speed·episodes·fullscreen. */
"use strict";

(function () {
  let P = null; // player state

  function fmt(t) {
    if (!isFinite(t) || t < 0) t = 0;
    const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = Math.floor(t % 60);
    return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(s).padStart(2, "0");
  }

  function resolveSrc(link, referer, forceProxy) {
    // r2.dev: direct object storage — verified 206 video/mkv, no referer needed, quota-safe.
    // Everything else (busycdn/multidownload/etc): /api/proxy rewrites UA+referer,
    // follows redirects (verified 206 video/matroska through 302 chain).
    if (!forceProxy && /pub-[a-z0-9-]+\.r2\.dev/i.test(link)) return link;
    return "/api/proxy?u=" + encodeURIComponent(link) + (referer ? "&ref=" + encodeURIComponent(referer) : "");
  }

  window.__openPlayer = function (ctx) {
    // ctx: {sourceId,url,name,poster,ep,quality,link,linkLabel,referer,direct}
    closePlayer();
    const src = resolveSrc(ctx.link, ctx.referer); // eslint-disable-line (kept for clarity)
    void src;
    const hevcTag = /hevc|h\.265|x265/i.test(ctx.linkLabel || "") || /hevc/i.test(ctx.qualityLabel || "");

    $playerRoot.innerHTML = `
    <div class="player-page" id="player-page">
      <video id="pv" playsinline preload="metadata"></video>
      <div class="player-loading" id="pload">
        <div class="spinner"></div>
        <div style="font-size:13px;color:#ddd" id="pload-msg">${hevcTag ? "HEVC stream — testing hardware decode…" : "Loading…"}</div>
        <button class="skip-btt" id="pskip" style="display:none">Skip 85s</button>
      </div>
      <div class="player-overlay visible" id="pov">
        <div class="scrim-top"></div>
        <div class="scrim-bottom"></div>
        <div class="player-top">
          <button class="icon-btt" id="p-back">${ICONS.back}</button>
          <div class="titles">
            <div class="title">${esc(ctx.name)}${ctx.ep ? " · " + esc(ctx.ep) : ""}</div>
            <div class="info" id="p-info">${esc(ctx.linkLabel || "")}</div>
          </div>
        </div>
        <div class="player-center">
          <button class="pb-btt small" id="p-rw">${ICONS.replay10}</button>
          <button class="pb-btt big" id="p-play">${ICONS.pause}</button>
          <button class="pb-btt small" id="p-fw">${ICONS.fwd10}</button>
        </div>
        <div class="player-bottom">
          <div class="player-times">
            <span id="p-cur">0:00</span>
            <span id="p-dur">0:00</span>
          </div>
          <div class="seekbar" id="p-seek">
            <div class="track"><div class="buffer" id="p-buf"></div><div class="played" id="p-played"></div></div>
            <div class="knob" id="p-knob"></div>
          </div>
          <div class="player-actions">
            <button class="icon-btt" id="p-speed" title="Playback speed"><span id="p-speed-label" style="font-size:12.5px;color:#fff">1x</span></button>
            <button class="icon-btt" id="p-quality" title="Source info">${ICONS.quality}</button>
            <span class="spacer"></span>
            <button class="icon-btt" id="p-fs">${ICONS.fullscreen}</button>
          </div>
        </div>
      </div>
    </div>`;

    const page = document.getElementById("player-page");
    const v = document.getElementById("pv");
    const ov = document.getElementById("pov");
    const load = document.getElementById("pload");
    const loadMsg = document.getElementById("pload-msg");
    document.documentElement.style.overflow = "hidden";

    P = { ctx, v, ov, hideT: null, speedIdx: 0 };
    const SPEEDS = [1, 1.25, 1.5, 2, 0.75];
    const SPEED_LABELS = ["1x", "1.25x", "1.5x", "2x", "0.75x"];

    // ---- source
    P.triedDirect = false;
    P.triedProxy = false;
    v.src = resolveSrc(ctx.link, ctx.referer);
    if (hevcTag) loadMsg.textContent = "HEVC — trying hardware decode…";
    else if (/\.mkv|matroska/i.test(ctx.link) && !v.canPlayType('video/x-matroska; codecs="avc1.42E01E"'))
      loadMsg.textContent = "MKV stream — browser lacks Matroska demuxer, trying…";

    v.onerror = () => {
      // auto-fallback: direct <-> proxy, once each
      const isProxied = v.src.startsWith("/api/proxy");
      if (isProxied && !P.triedDirect && /r2\.dev/i.test(ctx.link)) {
        P.triedDirect = true;
        loadMsg.textContent = "Retrying direct…";
        v.src = ctx.link;
        v.play().catch(() => {});
        return;
      }
      if (!isProxied && !P.triedProxy) {
        P.triedProxy = true;
        loadMsg.textContent = "Retrying via proxy…";
        v.src = resolveSrc(ctx.link, ctx.referer, true);
        v.play().catch(() => {});
        return;
      }
      const mkvHint = (v.error && v.error.code === 4)
        ? "This browser can't decode this format here (MKV/HEVC). Chrome desktop, Safari and most mobile browsers with hardware decode will play it."
        : "The stream link may have expired — sources refresh on every view.";
      load.innerHTML = `<div style="padding:0 26px;text-align:center">
          <div style="font-size:40px">🎬</div>
          <div style="font-size:15px;color:#fff;margin:10px 0 6px;font-weight:600">Playback failed</div>
          <div style="font-size:13px;color:#bbb;line-height:1.5">${mkvHint}</div>
          <div style="margin-top:14px;display:flex;gap:10px;justify-content:center;flex-wrap:wrap">
            <button class="skip-btt" id="p-othersrc">Pick another source</button>
            <button class="skip-btt" id="p-openraw">Open direct link</button>
            <button class="skip-btt" id="p-retry">Retry</button>
          </div></div>`;
      const oraw = load.querySelector("#p-openraw");
      if (oraw) oraw.onclick = () => window.open(ctx.link, "_blank");
      const retr = load.querySelector("#p-retry");
      if (retr) retr.onclick = () => window.__openPlayer(ctx);
      const oth = load.querySelector("#p-othersrc");
      if (oth) oth.onclick = () => { closePlayer(); history.back(); };
    };

    v.addEventListener("loadedmetadata", () => {
      load.style.display = "none";
      document.getElementById("p-dur").textContent = fmt(v.duration);
      scheduleHide();
    });
    v.addEventListener("waiting", () => { load.style.display = "flex"; });
    v.addEventListener("playing", () => {
      load.style.display = "none";
      document.getElementById("p-play").innerHTML = ICONS.pause;
      scheduleHide();
    });
    v.addEventListener("pause", () => {
      document.getElementById("p-play").innerHTML = ICONS.play;
      show();
    });
    v.addEventListener("timeupdate", () => {
      const d = v.duration || 0;
      const pct = d ? (v.currentTime / d) * 100 : 0;
      document.getElementById("p-cur").textContent = fmt(v.currentTime);
      document.getElementById("p-played").style.width = pct + "%";
      document.getElementById("p-knob").style.left = pct + "%";
      try {
        if (v.buffered.length) {
          const end = v.buffered.end(v.buffered.length - 1);
          document.getElementById("p-buf").style.width = (d ? (end / d) * 100 : 0) + "%";
        }
      } catch {}
      if (d && Math.floor(v.currentTime) % 5 === 0) {
        saveProgress({ ...P.ctx, position: v.currentTime, duration: d });
      }
    });

    // ---- controls
    const show = () => { ov.classList.add("visible"); scheduleHide(); };
    const scheduleHide = () => {
      clearTimeout(P.hideT);
      P.hideT = setTimeout(() => {
        if (!v.paused) ov.classList.remove("visible");
      }, 3200);
    };
    page.addEventListener("click", (e) => {
      if (e.target === v) {
        if (ov.classList.contains("visible") && !v.paused) { ov.classList.remove("visible"); clearTimeout(P.hideT); }
        else show();
      }
    });
    const seekBy = (dt) => { v.currentTime = Math.max(0, Math.min((v.duration || 0) - .5, v.currentTime + dt)); show(); };
    document.getElementById("p-rw").onclick = () => seekBy(-10);
    document.getElementById("p-fw").onclick = () => seekBy(10);
    const playBtn = document.getElementById("p-play");
    playBtn.onclick = () => { v.paused ? v.play().catch(() => {}) : v.pause(); };
    document.getElementById("p-back").onclick = closePlayer;
    document.getElementById("p-speed").onclick = () => {
      P.speedIdx = (P.speedIdx + 1) % SPEEDS.length;
      v.playbackRate = SPEEDS[P.speedIdx];
      document.getElementById("p-speed-label").textContent = SPEED_LABELS[P.speedIdx];
      show();
    };
    document.getElementById("p-quality").onclick = () => toast((P.ctx.linkLabel || "Source") + (hevcTag ? " · HEVC" : ""));
    const fsBtn = document.getElementById("p-fs");
    fsBtn.onclick = () => {
      if (document.fullscreenElement) document.exitFullscreen();
      else page.requestFullscreen && page.requestFullscreen();
    };
    document.addEventListener("fullscreenchange", () => {
      fsBtn.innerHTML = document.fullscreenElement ? ICONS.fullscreenExit : ICONS.fullscreen;
    });

    // double-tap left/right = ±10s (mobile)
    let lastTap = 0, lastX = 0;
    page.addEventListener("touchend", (e) => {
      if (e.target.closest(".player-top,.player-bottom,.pb-btt,.player-loading")) return;
      const now = Date.now();
      const x = e.changedTouches[0].clientX;
      if (now - lastTap < 320) {
        if (x < innerWidth * 0.4) seekBy(-10);
        else if (x > innerWidth * 0.6) seekBy(10);
        lastTap = 0;
      } else { lastTap = now; lastX = x; }
    });

    // seekbar dragging
    const seekEl = document.getElementById("p-seek");
    const seekTo = (clientX) => {
      const r = seekEl.getBoundingClientRect();
      const pct = Math.max(0, Math.min(1, (clientX - r.left) / r.width));
      if (v.duration) v.currentTime = pct * v.duration;
      show();
    };
    let dragging = false;
    seekEl.addEventListener("pointerdown", (e) => { dragging = true; seekEl.setPointerCapture(e.pointerId); seekTo(e.clientX); });
    seekEl.addEventListener("pointermove", (e) => { if (dragging) seekTo(e.clientX); });
    seekEl.addEventListener("pointerup", () => { dragging = false; });

    // keyboard (CloudStream desktop-ish)
    const keymap = (e) => {
      if (!P) return;
      if (e.key === " " || e.key === "k") { e.preventDefault(); v.paused ? v.play().catch(() => {}) : v.pause(); }
      else if (e.key === "ArrowRight") seekBy(10);
      else if (e.key === "ArrowLeft") seekBy(-10);
      else if (e.key === "Escape" && !document.fullscreenElement) closePlayer();
      else if (e.key === "f") fsBtn.onclick();
    };
    document.addEventListener("keydown", keymap);
    P.keymap = keymap;

    v.play().catch(() => {
      // autoplay blocked — show big play
      document.getElementById("p-play").innerHTML = ICONS.play;
      show();
    });

    // resume position
    const prog = LS.get("progress", []).find((x) => x.url === ctx.url && x.link === ctx.link && x.ep === ctx.ep);
    if (prog && prog.position > 30 && prog.position < (prog.duration || 1e9) - 60) {
      v.addEventListener("loadedmetadata", () => { v.currentTime = prog.position; toast("Resumed at " + fmt(prog.position)); }, { once: true });
    }
  };

  function closePlayer() {
    if (!P) return;
    const { v, keymap } = P;
    v.pause();
    v.removeAttribute("src");
    v.load();
    document.removeEventListener("keydown", keymap);
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    document.documentElement.style.overflow = "";
    $playerRoot.innerHTML = "";
    P = null;
  }
})();
