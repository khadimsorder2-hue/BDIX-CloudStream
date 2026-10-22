/* ==========================================================================
 * SkStream Web Player  —  standalone premium player module
 * Pixel-copy of the CloudStream (ExoPlayer) player: bottom control bar with
 * buffered seekbar + dual timestamps, big center play/pause with ripple,
 * double-tap ±10s, long-press 2x speed, skip-intro (+85s), lock, loop,
 * bottom-sheet menus (speed / quality / subtitles / audio / episodes),
 * top-bar SOURCE chip + dropdown (server selection), bottom-row
 * Fit/Crop/Stretch cycling button, top bar with back + title, loading
 * spinner, error failover,
 * hls.js for m3u8, HEVC capability probe, mkv remux hook (mediabunny
 * vendor if present), resume + progress callbacks.
 *
 * Usage:
 *   const c = SkStreamPlayer.mount(containerEl, {
 *     links: [{label, url, quality, referer}],
 *     title, subtitle, poster, subtitles: [{name,url}],
 *     episodes: [{name}], episodeIndex,
 *     onBack, onEpisode(i), onProgress({position,duration,linkIndex}),
 *     resumePosition, skipIntro: [from, to]
 *   });
 *   c.destroy();
 * ========================================================================== */
(function () {
  "use strict";

  var VERSION = "1.2.0";
  var ICONS = {
    fs: '<svg viewBox="0 0 24 24"><path d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"/></svg>',
    back: '<svg viewBox="0 0 24 24"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>',
    play: '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>',
    pause: '<svg viewBox="0 0 24 24"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>',
    rw: '<svg viewBox="0 0 24 24"><path d="M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z"/></svg>',
    ff: '<svg viewBox="0 0 24 24"><path d="M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z"/></svg>',
    dots: '<svg viewBox="0 0 24 24"><path d="M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/></svg>',
    speed: '<svg viewBox="0 0 24 24"><path d="M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z"/></svg>',
    hd: '<svg viewBox="0 0 24 24"><path d="M19 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm-8 7H9.5v-.5h-2v3h2V13H11v1a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v1zm7 0h-1.5v-.5h-2v3h2V13H18v1a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v1z"/></svg>',
    cc: '<svg viewBox="0 0 24 24"><path d="M19 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm-8 7H9.5v-.5h-2v3h2V13H11v1a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v1zm7 0h-1.5v-.5h-2v3h2V13H18v1a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v1z"/></svg>',
    audio: '<svg viewBox="0 0 24 24"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/></svg>',
    eps: '<svg viewBox="0 0 24 24"><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h10v2H4z"/></svg>',
    aspect: '<svg viewBox="0 0 24 24"><path d="M19 12h-2v3h-3v2h5v-5zM7 9h3V7H5v5h2V9zm14-6H3a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h18a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm0 16H3V5h18v14z"/></svg>',
    caret: '<svg viewBox="0 0 24 24"><path d="M7 10l5 5 5-5z"/></svg>',
    loop: '<svg viewBox="0 0 24 24"><path d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z"/></svg>',
    lock: '<svg viewBox="0 0 24 24"><path d="M18 8h-1V6a5 5 0 0 0-10 0v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10a2 2 0 0 0-2-2zM9 6a3 3 0 0 1 6 0v2H9V6zm3 11a2 2 0 1 1 0-4 2 2 0 0 1 0 4z"/></svg>',
    unlock: '<svg viewBox="0 0 24 24"><path d="M12 17a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm6-9h-1V6a5 5 0 0 0-9.9-1h2.1A3 3 0 0 1 15 6v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10a2 2 0 0 0-2-2zm0 12H6V10h12v10z"/></svg>',
    check: '<svg class="csp-check" viewBox="0 0 24 24"><path d="M9 16.2L4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4L9 16.2z"/></svg>',
    close: '<svg viewBox="0 0 24 24"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>',
    warn: '<svg viewBox="0 0 24 24"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/></svg>',
    sun: '<svg viewBox="0 0 24 24"><path d="M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10zM2 13h2a1 1 0 1 0 0-2H2a1 1 0 1 0 0 2zm18 0h2a1 1 0 1 0 0-2h-2a1 1 0 1 0 0 2zM11 2v2a1 1 0 1 0 2 0V2a1 1 0 1 0-2 0zm0 18v2a1 1 0 1 0 2 0v-2a1 1 0 1 0-2 0zM5.99 4.58a1 1 0 1 0-1.41 1.41l1.06 1.06a1 1 0 1 0 1.41-1.41L5.99 4.58zm12.37 12.37a1 1 0 1 0-1.41 1.41l1.06 1.06a1 1 0 1 0 1.41-1.41l-1.06-1.06zm1.06-10.96a1 1 0 1 0-1.41-1.41l-1.06 1.06a1 1 0 1 0 1.41 1.41l1.06-1.06zM7.05 18.36a1 1 0 1 0-1.41-1.41l-1.06 1.06a1 1 0 1 0 1.41 1.41l1.06-1.06z"/></svg>',
    vol: '<svg viewBox="0 0 24 24"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z"/></svg>',
    volx: '<svg viewBox="0 0 24 24"><path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51A8.8 8.8 0 0 0 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06a8.99 8.99 0 0 0 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"/></svg>',
    pip: '<svg viewBox="0 0 24 24"><path d="M19 11h-8v6h8v-6zm4 8V4.98C23 3.88 22.1 3 21 3H3c-1.1 0-2 .88-2 1.98V19c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2zm-2 .02H3V4.97h18v14.05z"/></svg>',
  };
  var SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];
  // aspect cycle — user spec: playback starts in FILL (screen-filled) mode
  var ASPECTS = [
    { label: "Fill", cls: "csp-aspect-fill" },
    { label: "Fit", cls: "csp-aspect-fit" },
    { label: "Crop", cls: "csp-aspect-cover" },
  ];
  // per-source retry policy (user spec): a failing source is retried 2 times
  // with a minimum 3-5s gap before moving to the next source; after the last
  // source fails the player stops completely (no silent infinite cycling)
  var RETRY_MAX = 2;
  var RETRY_DELAY = 4000;

  // ----------------------------------------------------------------- utils
  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }
  function fmt(t) {
    if (!isFinite(t) || t < 0) t = 0;
    var s = Math.floor(t % 60), m = Math.floor((t / 60) % 60), h = Math.floor(t / 3600);
    var mm = String(m).padStart(2, "0"), ss = String(s).padStart(2, "0");
    return h > 0 ? h + ":" + mm + ":" + ss : m + ":" + ss;
  }
  function hevcSupported() {
    try {
      var v = document.createElement("video");
      if (v.canPlayType('video/mp4; codecs="hvc1.1.6.L93.B0"') !== "") return true;
      if (v.canPlayType('video/mp4; codecs="hev1.1.6.L93.B0"') !== "") return true;
      if (window.MediaSource && MediaSource.isTypeSupported('video/mp4; codecs="hvc1.1.6.L93.B0"')) return true;
    } catch (e) { /* ignore */ }
    return false;
  }

  function loadScript(src) {
    return new Promise(function (res, rej) {
      if (document.querySelector('script[src="' + src + '"]')) return res();
      var s = document.createElement("script");
      s.src = src; s.onload = res; s.onerror = rej;
      document.head.appendChild(s);
    });
  }

  // ================================================================ mount
  function mount(root, opts) {
    opts = opts || {};
    var links = (opts.links || []).slice();
    var linkIndex = Math.min(opts.startIndex || 0, Math.max(links.length - 1, 0));
    var controller = {};

    root.classList.add("csp-root");
    root.innerHTML = "";

    // ----- video layers
    var video = el("video", "csp-video csp-aspect-fill");
    video.playsInline = true;
    video.setAttribute("playsinline", "");
    video.preload = "auto";
    video.crossOrigin = "anonymous";
    root.appendChild(video);

    var mseHolder = el("div", "csp-mse-canvas");
    mseHolder.style.display = "none";
    root.appendChild(mseHolder);

    // ----- scrims / chrome
    var scrimTop = el("div", "csp-scrim-top");
    var scrimBottom = el("div", "csp-scrim-bottom");
    root.appendChild(scrimTop); root.appendChild(scrimBottom);

    var top = el("div", "csp-top");
    var backBtn = el("button", "csp-icon-btn", ICONS.back);
    var titleBox = el("div", "csp-title-box");
    var titleEl = el("div", "csp-title", escapeHtml(opts.title || ""));
    var subEl = el("div", "csp-subtitle", escapeHtml(opts.subtitle || ""));
    if (!opts.subtitle) subEl.style.display = "none";
    titleBox.appendChild(titleEl); titleBox.appendChild(subEl);
    var moreBtn = el("button", "csp-icon-btn", ICONS.dots);
    // source selection chip lives IN the top bar (user spec — back at the top)
    var srcChip = el("button", "csp-src-chip");
    var srcDot = el("span", "csp-qdot");
    var srcLabel = el("span", "csp-src-label", "Source");
    var srcCaret = el("span", "csp-src-caret", ICONS.caret);
    srcChip.appendChild(srcDot); srcChip.appendChild(srcLabel); srcChip.appendChild(srcCaret);
    top.appendChild(backBtn); top.appendChild(titleBox); top.appendChild(srcChip); top.appendChild(moreBtn);
    root.appendChild(top);

    var center = el("div", "csp-center");
    var playBig = el("button", "csp-play-big", ICONS.play);
    center.appendChild(playBig);
    root.appendChild(center);

    var lockWrap = el("div", "csp-lock");
    var lockBtn = el("button", "csp-icon-btn", ICONS.unlock);
    lockWrap.appendChild(lockBtn);
    root.appendChild(lockWrap);

    // bottom
    var bottom = el("div", "csp-bottom");
    var timerow = el("div", "csp-timerow");
    var curEl = el("span", null, "0:00");
    var durEl = el("span", "csp-dur", "0:00");
    timerow.appendChild(curEl); timerow.appendChild(durEl);

    var seekbar = el("div", "csp-seekbar");
    var track = el("div", "csp-seek-track");
    var bufBar = el("div", "csp-seek-buffer");
    var playedBar = el("div", "csp-seek-played");
    var thumb = el("div", "csp-seek-thumb");
    track.appendChild(bufBar); track.appendChild(playedBar); track.appendChild(thumb);
    seekbar.appendChild(track);

    var btnrow = el("div", "csp-btnrow");
    var playBtn = el("button", "csp-btn", ICONS.play);
    var rwBtn = el("button", "csp-btn", ICONS.rw);
    var ffBtn = el("button", "csp-btn", ICONS.ff);
    var loopBtn = el("button", "csp-btn csp-optional", ICONS.loop);
    var spacer = el("div", "csp-spacer");
    var speedBtn = el("button", "csp-btn", ICONS.speed);
    var resizeBtn = el("button", "csp-btn", ICONS.aspect);
    resizeBtn.title = "Fit / Fill";
    var qBtn = el("button", "csp-btn", ICONS.hd);
    var ccBtn = el("button", "csp-btn", ICONS.cc);
    var audioBtn = el("button", "csp-btn csp-optional", ICONS.audio);
    var epsBtn = el("button", "csp-btn csp-eps-btn", ICONS.eps);
    var skipBtn = el("button", "csp-skipintro", "Skip Intro · +85s");
    [playBtn, rwBtn, ffBtn, loopBtn, spacer, speedBtn, resizeBtn, qBtn, ccBtn, audioBtn, epsBtn]
      .forEach(function (b) { btnrow.appendChild(b); });
    bottom.appendChild(timerow); bottom.appendChild(seekbar); bottom.appendChild(btnrow);
    root.appendChild(bottom);
    // skip intro floats above the bottom bar, right aligned (CS position)
    skipBtn.style.position = "absolute";
    skipBtn.style.right = "14px";
    skipBtn.style.bottom = "86px";
    root.appendChild(skipBtn);

    // hud elements
    var gesture = el("div", "csp-gesture");
    gesture.innerHTML = '<svg></svg><div class="csp-g-bar"><i></i></div><div class="csp-g-value"></div>';
    root.appendChild(gesture);
    var speedChip = el("div", "csp-speed-chip", "2x speed");
    root.appendChild(speedChip);
    var toastEl = el("div", "csp-toast");
    root.appendChild(toastEl);

    var loading = el("div", "csp-loading", '<div class="csp-spinner"></div><span>Loading video…</span>');
    root.appendChild(loading);
    var skipLoadBtn = el("button", "csp-skipintro", "Skip loading · next source");
    skipLoadBtn.style.position = "absolute";
    skipLoadBtn.style.right = "14px";
    skipLoadBtn.style.top = "64px";
    skipLoadBtn.onclick = function () {
      // user asked for a way out of a slow source — jump straight to the next
      // one (bypasses the per-source retry delays)
      clearLoadTimers();
      S.srcRetry = RETRY_MAX;
      failCurrent("Skipped loading");
    };
    root.appendChild(skipLoadBtn);
    var errBox = el("div", "csp-error");
    root.appendChild(errBox);

    // sheets
    var sheetScrim = el("div", "csp-sheet-scrim");
    var sheet = el("div", "csp-sheet");
    root.appendChild(sheetScrim); root.appendChild(sheet);

    // top source dropdown — drops down from under the top-bar chip
    var srcScrim = el("div", "csp-srcmenu-scrim");
    var srcMenu = el("div", "csp-srcmenu");
    root.appendChild(srcScrim); root.appendChild(srcMenu);

    // ---------------------------------------------------------- state
    var S = {
      playing: false, waiting: false, duration: 0, position: 0, buffered: 0,
      locked: false, looping: false, aspectIdx: 0, speedIdx: 2,
      hideTimer: 0, started: false, destroyed: false,
      longPressTimer: 0, longPressActive: false, speedBeforePress: 2,
      subOffset: 0,
      hls: null, remux: null, objectUrl: null,
      srcRetry: 0, retryTimer: 0, autoRefreshed: false,
      hevcOk: hevcSupported(),
      progressTimer: 0, firstPlay: true,
      bufferIdx: 1, hlsLevels: [], hlsLevelIdx: -1, subScaleIdx: 1,
      loadTimer: 0, skipLoadTimer: 0, stallTimer: 0,
      lastDataTs: 0, nudged: false, fsAutoTried: false, fsAutoDone: false,
      hadFs: false, userFsExit: false,
    };
    if (opts.skipIntro == null) opts.skipIntro = [0, 85];

    // ------------------------------------------------------ dom helpers
    function escapeHtml(s) {
      return String(s).replace(/[&<>"']/g, function (c) {
        return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
      });
    }
    var toastTimer = 0;
    function toast(msg, ms) {
      toastEl.textContent = msg;
      toastEl.classList.add("csp-on");
      clearTimeout(toastTimer);
      toastTimer = setTimeout(function () { toastEl.classList.remove("csp-on"); }, ms || 2600);
    }
    function showControls() {
      root.classList.remove("csp-controls-hidden");
      clearTimeout(S.hideTimer);
      // user spec: any touch activity makes the controls visible and they stay
      // visible for a FULL 15s — auto-hide only fires when the screen has had
      // no touch at all for 15s (video playing; paused keeps them up; an open
      // sheet is never yanked away; lock icon also hides after the 15s).
      S.hideTimer = setTimeout(function () {
        if (!video.paused && !sheetOpen) root.classList.add("csp-controls-hidden");
      }, 15000);
    }
    function hideControls() { root.classList.add("csp-controls-hidden"); }
    function toggleControls() {
      if (root.classList.contains("csp-controls-hidden")) showControls();
      else hideControls();
    }

    // --------------------------------------- fullscreen + orientation lock
    var isCoarse = false;
    try { isCoarse = window.matchMedia && window.matchMedia("(pointer: coarse)").matches; } catch (e) {}
    function fsActive() {
      return !!(document.fullscreenElement || document.webkitFullscreenElement);
    }
    function lockLandscape() {
      try {
        if (screen.orientation && screen.orientation.lock) {
          var pr = screen.orientation.lock("landscape");
          if (pr && pr.catch) pr.catch(function () { /* desktop / iOS: no-op */ });
        }
      } catch (e) { /* unsupported */ }
    }
    function unlockOrientation() {
      try { if (screen.orientation && screen.orientation.unlock) screen.orientation.unlock(); } catch (e) {}
    }
    function goFullscreenLandscape(onDone) {
      function done(ok) { try { if (onDone) onDone(!!ok); } catch (e) {} }
      try {
        if (fsActive()) { lockLandscape(); done(true); return; }
        var req = root.requestFullscreen || root.webkitRequestFullscreen;
        if (!req) { lockLandscape(); done(false); return; }
        var p = req.call(root);
        if (p && p.then) p.then(function () { lockLandscape(); done(true); }).catch(function () { done(false); });
        else { lockLandscape(); done(true); }
      } catch (e) { done(false); }
    }
    function exitFullscreenQuiet() {
      // mark OUR exits so the fullscreenchange listener doesn't treat them
      // as "user pressed back while fullscreen" and close the player
      S.userFsExit = true;
      try {
        if (fsActive()) {
          var p = document.exitFullscreen ? document.exitFullscreen() : null;
          if (p && p.catch) p.catch(function () {});
          if (document.webkitExitFullscreen) document.webkitExitFullscreen();
        }
      } catch (e) {}
      unlockOrientation();
    }
    // Android back button while in fullscreen: the browser exits fullscreen
    // WITHOUT firing popstate. When that unexpected exit happens (not caused
    // by our own exitFullscreenQuiet), closing the player takes the user
    // straight back to the movie info page — exactly the requested behavior:
    // landscape back → info page, no intermediate portrait player step.
    function onFsChange() {
      if (S.destroyed) return;
      if (fsActive()) { S.hadFs = true; S.userFsExit = false; return; }
      if (S.userFsExit) { S.userFsExit = false; return; }
      if (S.hadFs && typeof opts.onBack === "function") {
        try { opts.onBack(); } catch (e) {}
      }
    }
    document.addEventListener("fullscreenchange", onFsChange);
    document.addEventListener("webkitfullscreenchange", onFsChange);
    root.addEventListener("pointermove", function () { showControls(); });
    // EVERY touch (background or on real UI) re-arms the 15s hide timer —
    // capture phase so it runs before any button's own handler.
    root.addEventListener("pointerdown", function () { showControls(); }, true);
    // desktop only: moving the mouse off the video hides the bar instantly;
    // on touch devices pointerleave fires right after every tap, which made
    // controls "instantly hide" — never hide-on-leave for coarse pointers.
    root.addEventListener("pointerleave", function () {
      if (isCoarse) return;
      if (!video.paused) hideControls();
    });
    // desktop single click toggles controls (touch path handles its own tap)
    var lastTouchTs = 0;
    // taps on real UI (bars, buttons, sheets, source menu, seekbar) must never
    // fall through to the background "toggle controls / gesture" actions
    var UI_SEL = "button, .csp-top, .csp-bottom, .csp-lock, .csp-sheet, .csp-sheet-scrim, .csp-srcmenu, .csp-srcmenu-scrim, .csp-seekbar, .csp-error, .csp-loading, .csp-skipintro, .csp-speed-chip";
    function onUi(e) {
      return !!(e.target && e.target.closest && e.target.closest(UI_SEL));
    }
    root.addEventListener("click", function (e) {
      if (onUi(e)) return;
      if (Date.now() - lastTouchTs < 600) return;
      if (S.locked || sheetOpen) { if (S.locked) toast("Screen locked"); return; }
      toggleControls();
    });

    function ping() {
      var p = el("div", "csp-ping");
      playBig.appendChild(p);
      setTimeout(function () { p.remove(); }, 520);
    }
    function setPlayIcon() {
      playBig.innerHTML = video.paused ? ICONS.play : ICONS.pause;
      playBtn.innerHTML = video.paused ? ICONS.play : ICONS.pause;
    }
    function seekHint(side) {
      var h = el("div", "csp-seek-hint " + side,
        (side === "left" ? ICONS.rw : ICONS.ff) + "<span>" + (side === "left" ? "-10s" : "+10s") + "</span>");
      root.appendChild(h);
      setTimeout(function () { h.remove(); }, 560);
    }

    // --------------------------------------------------------- progress UI
    function renderTime() {
      curEl.textContent = fmt(S.position);
      durEl.textContent = "-" + fmt(Math.max(0, S.duration - S.position));
      var pct = S.duration ? (S.position / S.duration) * 100 : 0;
      playedBar.style.width = pct + "%";
      thumb.style.left = pct + "%";
      bufBar.style.width = (S.duration ? (S.buffered / S.duration) * 100 : 0) + "%";
      playedBar.classList.toggle("csp-intro-skip",
        inIntroWindow() && !S.playing === false);
      skipBtn.classList.toggle("csp-on", inIntroWindow());
    }
    function inIntroWindow() {
      var w = opts.skipIntro || [0, 85];
      return S.duration > 120 && S.position >= w[0] && S.position <= w[1] && S.position < S.duration;
    }

    // ------------------------------------------------------------ engines
    function teardownEngine() {
      if (S.hls) { try { S.hls.destroy(); } catch (e) {} S.hls = null; }
      if (S.remux && S.remux.abort) { try { S.remux.abort(); } catch (e) {} }
      S.remux = null;
      if (S.objectUrl) { URL.revokeObjectURL(S.objectUrl); S.objectUrl = null; }
      mseHolder.style.display = "none"; mseHolder.innerHTML = "";
      video.style.display = "";
      // clear tracks
      while (video.firstChild) video.removeChild(video.firstChild);
      video.removeAttribute("src");
      try { video.load(); } catch (e) {}
    }

    function proxied(link) {
      if (!link) return "";
      // /api/links mints signed, tokenized proxy URLs — use them as-is
      if (link.playUrl) return link.playUrl;
      // local/relative paths (demo page, pre-bundled assets) play directly
      if (!/^https?:/i.test(link.url)) return link.url;
      var u = "/api/proxy?u=" + encodeURIComponent(link.url);
      if (link.referer) u += "&referer=" + encodeURIComponent(link.referer);
      return u;
    }
    // subtitles need a signed URL too (ask the server to mint one)
    function signedUrl(url, cb) {
      try {
        fetch("/api/sign?u=" + encodeURIComponent(url))
          .then(function (r) { return r.json(); })
          .then(function (j) { cb(j.url || url); })
          .catch(function () { cb(url); });
      } catch (e) { cb(url); }
    }

    function useLink(i, keepPos) {
      if (S.destroyed) return;
      i = Math.max(0, Math.min(i, links.length - 1));
      linkIndex = i;
      var link = links[i];
      var pos = keepPos ? S.position : (opts.resumePosition || 0);
      S.firstPlay = !keepPos;
      teardownEngine();
      errBox.classList.remove("csp-on");
      loading.classList.add("csp-on");
      loading.querySelector("span").textContent = "Loading video…";
      S.started = false;

      var src = link.isPage ? link.url : proxied(link);

      var isHls = /\.m3u8(\?|$)/i.test(link.url);
      if (isHls) {
        setupHls(src, pos);
      } else {
        setupNative(src, pos, link);
      }
      renderQualityMenu();
      renderSrcChip();
      armLoadTimers();
      showControls();
    }

    function setupNative(src, pos, link) {
      video.src = src;
      if (/\.mkv(\?|$)/i.test(link.url) || /matroska/i.test(link.mime || "")) {
        // hint the demuxer; Chromium plays mkv/h264, Safari/Firefox will error
        video.type = "video/x-matroska; codecs=\u0022h264,eac3\u0022";
      }
      var onLoaded = function () {
        video.removeEventListener("loadedmetadata", onLoaded);
        video.playbackRate = SPEEDS[S.speedIdx]; // keep speed across source switches
        if (pos > 0 && pos < (video.duration || 1e9) - 10) {
          try { video.currentTime = pos; } catch (e) {}
        }
        if (opts.autoPlay !== false) {
          var p = video.play();
          if (p && p.catch) p.catch(function () { /* autoplay blocked */ });
        }
      };
      video.addEventListener("loadedmetadata", onLoaded);
      if (link.mime) {
        var probe = el("source");
        probe.src = src; probe.type = link.mime;
        video.appendChild(probe);
      }
    }

    function setupHls(src, pos) {
      var start = function () {
        if (!window.Hls || !Hls.isSupported()) {
          if (video.canPlayType("application/vnd.apple.mpegurl")) {
            video.src = src; nativeFallback(pos);
          } else failCurrent("HLS not supported in this browser");
          return;
        }
        var hls = new Hls({
          maxBufferLength: [15, 40, 90][S.bufferIdx] || 40,
          maxMaxBufferLength: 120,
          backBufferLength: 60,
          enableWorker: true,
          lowLatencyMode: false,
          startLevel: -1,
          abrEwmaDefaultEstimate: 1000000,
          manifestLoadingMaxRetry: 2,
          levelLoadingMaxRetry: 4,
          fragLoadingMaxRetry: 6,
        });
        S.hls = hls;
        S.hlsLevels = []; S.hlsLevelIdx = -1;
        hls.loadSource(src);
        hls.attachMedia(video);
        hls.on(Hls.Events.MANIFEST_PARSED, function () {
          try {
            S.hlsLevels = (hls.levels || []).map(function (l, i) {
              return { i: i, h: l.height || 0, br: l.bitrate || 0 };
            }).filter(function (l) { return l.h || l.br; });
          } catch (e) { S.hlsLevels = []; }
          if (pos > 0) video.currentTime = pos;
          video.playbackRate = SPEEDS[S.speedIdx];
          if (opts.autoPlay !== false) video.play().catch(function () {});
        });
        hls.on(Hls.Events.LEVEL_SWITCHED, function (_, d) {
          S.hlsLevelIdx = hls.autoLevelEnabled ? -1 : d.level;
        });
        hls.on(Hls.Events.ERROR, function (_, data) {
          if (data.fatal) {
            if (data.type === Hls.ErrorTypes.NETWORK_ERROR) { try { hls.startLoad(); } catch (e) {} }
            else failCurrent("HLS error: " + data.details);
          }
        });
      };
      if (window.Hls) start();
      else loadScript("https://cdn.jsdelivr.net/npm/hls.js@1.5.17/dist/hls.min.js").then(start)
        .catch(function () { failCurrent("hls.js load failed"); });
    }
    function nativeFallback(pos) {
      video.addEventListener("loadedmetadata", function () {
        if (pos > 0) video.currentTime = pos;
        video.play().catch(function () {});
      }, { once: true });
    }

    // mkv note: Chromium plays matroska (h264/aac) natively — verified — so no
    // client-side remux is needed. A failing mkv goes through the standard
    // retry → next-source ladder in failCurrent().

    function failCurrent(reason) {
      if (S.destroyed) return;
      loading.classList.remove("csp-on");
      if (S.retryTimer) { clearTimeout(S.retryTimer); S.retryTimer = 0; }
      clearLoadTimers();
      // 1) per-source retries (user spec: 2 retries, minimum 3-5s gap)
      if (S.srcRetry < RETRY_MAX) {
        S.srcRetry++;
        loading.classList.add("csp-on");
        loading.querySelector("span").textContent =
          "Retrying source (" + S.srcRetry + "/" + RETRY_MAX + ")…";
        toast(reason + " — retry " + S.srcRetry + "/" + RETRY_MAX + " in 4s…");
        S.retryTimer = setTimeout(function () {
          S.retryTimer = 0;
          if (S.destroyed) return;
          useLink(linkIndex, true);
        }, RETRY_DELAY);
        return;
      }
      // 2) retries exhausted → next source; after the LAST source → stop
      S.srcRetry = 0;
      if (linkIndex + 1 < links.length) {
        toast(reason + " — trying next source…");
        useLink(linkIndex + 1, false);
        return;
      }
      showError(reason + " — all " + links.length + " sources failed");
    }
    function showError(reason) {
      errBox.innerHTML = "";
      errBox.appendChild(el("div", null, ICONS.warn));
      var p = el("p", null, "Playback failed: " + escapeHtml(reason));
      errBox.appendChild(p);
      var hint = S.hevcOk ? "" : "HEVC is not supported by this browser — Chrome/Edge on Windows/Mac or Android recommended.";
      errBox.appendChild(el("small", null, hint));
      var actions = el("div", "csp-error-actions");
      // one-shot auto re-extract: stale cache links are the most common cause
      // of "all sources failed" — ask the host to re-extract once, silently
      // (host passes autoRefresh=false when this movie was already refreshed)
      if (opts.autoRefresh !== false && !S.autoRefreshed && typeof opts.onRefreshSources === "function") {
        S.autoRefreshed = true;
        errBox.appendChild(el("p", null, "Refreshing sources…"));
        errBox.classList.add("csp-on");
        setTimeout(function () { if (!S.destroyed) opts.onRefreshSources(); }, 900);
        return;
      }
      var retry = el("button", "csp-btn-solid", "Retry");
      retry.onclick = function () { S.srcRetry = 0; useLink(linkIndex, false); };
      actions.appendChild(retry);
      if (linkIndex + 1 < links.length) {
        var next = el("button", "csp-btn-ghost", "Try next source");
        next.onclick = function () { S.srcRetry = 0; useLink(linkIndex + 1, false); };
        actions.appendChild(next);
      }
      if (typeof opts.onRefreshSources === "function") {
        var rf = el("button", "csp-btn-ghost", "Refresh sources");
        rf.onclick = function () {
          errBox.classList.remove("csp-on");
          loading.classList.add("csp-on");
          loading.querySelector("span").textContent = "Refreshing sources…";
          setTimeout(function () { if (!S.destroyed) opts.onRefreshSources(); }, 200);
        };
        actions.appendChild(rf);
      }
      errBox.appendChild(actions);
      errBox.classList.add("csp-on");
    }

    // ------------------------------------- loading / stall watchdogs
    function clearLoadTimers() {
      if (S.loadTimer) { clearTimeout(S.loadTimer); S.loadTimer = 0; }
      if (S.skipLoadTimer) { clearTimeout(S.skipLoadTimer); S.skipLoadTimer = 0; }
      skipLoadBtn.classList.remove("csp-on");
    }
    function armLoadTimers() {
      clearLoadTimers();
      // "Skip loading" pill after 6s (CloudStream parity)
      S.skipLoadTimer = setTimeout(function () {
        if (!S.started && !S.destroyed) skipLoadBtn.classList.add("csp-on");
      }, 6000);
      // auto failover if a source never starts within 14s — routed through
      // failCurrent so the per-source retry policy is respected
      S.loadTimer = setTimeout(function () {
        if (S.started || S.destroyed) return;
        failCurrent("Source too slow");
      }, 14000);
    }
    S.stallTimer = setInterval(function () {
      if (S.destroyed || !S.playing || !S.started) return;
      var stuck = Date.now() - S.lastDataTs;
      if (video.readyState < 3 && stuck > 6000 && !S.nudged) {
        S.nudged = true;
        toast("Recovering playback…");
        if (S.hls) { try { S.hls.startLoad(); } catch (e) {} }
        else { try { video.currentTime += 0.01; } catch (e) {} }
      } else if (video.readyState < 3 && stuck > 14000) {
        S.lastDataTs = Date.now();
        failCurrent("Source stalled");
      }
    }, 2500);

    // ------------------------------------------------------ video events
    video.addEventListener("play", function () {
      S.playing = true; setPlayIcon(); ping(); showControls();
      if (S.firstPlay) S.firstPlay = false;
      startProgressSave();
    });
    video.addEventListener("pause", function () { S.playing = false; setPlayIcon(); ping(); showControls(); stopProgressSave(); });
    video.addEventListener("waiting", function () { S.waiting = true; loading.classList.add("csp-on"); });
    video.addEventListener("playing", function () {
      S.waiting = false; loading.classList.remove("csp-on");
      S.started = true; S.srcRetry = 0;
      S.lastDataTs = Date.now(); S.nudged = false;
      clearLoadTimers();
      video.playbackRate = SPEEDS[S.speedIdx];
      // CloudStream parity: first playback goes fullscreen + landscape on phones
      if (isCoarse && !S.fsAutoTried) {
        S.fsAutoTried = true;
        goFullscreenLandscape(function (ok) { if (ok) { S.fsAutoDone = true; S.hadFs = true; } });
      }
    });
    video.addEventListener("timeupdate", function () {
      S.position = video.currentTime; S.duration = video.duration || 0;
      S.lastDataTs = Date.now();
      renderTime();
    });
    video.addEventListener("progress", function () {
      try {
        if (video.buffered.length) S.buffered = video.buffered.end(video.buffered.length - 1);
      } catch (e) {}
      renderTime();
    });
    video.addEventListener("durationchange", renderTime);
    video.addEventListener("error", function () {
      if (!video.src && !S.hls) return;
      // mkv plays natively in Chromium (h264/aac matroska) — verified — so an
      // error here is a real failure: run the retry → next-source ladder
      failCurrent("Source error");
    });
    video.addEventListener("volumechange", function () {
      muteBtn.innerHTML = video.muted || video.volume === 0 ? ICONS.volx : ICONS.vol;
    });
    video.addEventListener("ended", function () {
      saveProgress(true);
      showControls();
      if (S.looping) { video.currentTime = 0; video.play().catch(function () {}); }
      if (opts.onEnded) opts.onEnded();
    });

    // ------------------------------------------------------ progress save
    function startProgressSave() {
      stopProgressSave();
      S.progressTimer = setInterval(function () { saveProgress(false); }, 5000);
    }
    function stopProgressSave() { clearInterval(S.progressTimer); }
    function saveProgress(finished) {
      if (!opts.onProgress || !S.duration) return;
      try {
        opts.onProgress({
          position: video.currentTime,
          duration: S.duration,
          linkIndex: linkIndex,
          finished: !!finished,
        });
      } catch (e) {}
    }

    // ------------------------------------------------------------- sheets
    var sheetOpen = null;
    function openSheet(kind) {
      sheetOpen = kind;
      sheet.innerHTML = "";
      var head = el("div", "csp-sheet-head");
      head.appendChild(el("h3", null, sheetTitle(kind)));
      var closeB = el("button", "csp-icon-btn", ICONS.close);
      closeB.onclick = closeSheet;
      head.appendChild(closeB);
      sheet.appendChild(head);
      var body = el("div", "csp-sheet-body");
      sheet.appendChild(body);
      buildSheet[kind](body);
      sheetScrim.classList.add("csp-on");
      sheet.classList.add("csp-on");
      showControls();
    }
    function closeSheet() {
      sheetOpen = null;
      sheetScrim.classList.remove("csp-on");
      sheet.classList.remove("csp-on");
    }
    sheetScrim.addEventListener("click", closeSheet);
    function sheetTitle(kind) {
      return {
        speed: "Playback speed", quality: "Video source", subs: "Subtitles",
        audio: "Audio track", eps: "Episodes",
        more: "More options", info: "Now playing",
      }[kind] || "";
    }
    function item(parent, label, sub, icon, selected, onPick) {
      var b = el("button", "csp-sheet-item" + (selected ? " csp-selected" : ""),
        (icon || "") + "<span style='flex:1;min-width:0'>" + escapeHtml(label) +
        (sub ? "<small>" + sub + "</small>" : "") + "</span>" + (selected ? ICONS.check : ""));
      b.onclick = function () { onPick(); };
      parent.appendChild(b);
      return b;
    }

    // --------------------------------------- top-bar source chip + dropdown
    var srcMenuOpen = false;
    function qDotColor(l) {
      return l.quality >= 2160 ? "#E4D448" : l.quality >= 1080 ? "#48E484"
        : l.quality >= 720 ? "#3d50fa" : l.quality > 0 ? "#ea596e" : "#9ba0a4";
    }
    function renderSrcChip() {
      var l = links[linkIndex];
      if (!l) { srcChip.style.display = "none"; return; }
      srcChip.style.display = "";
      var q = l.quality && l.quality > 0 ? l.quality + "p" : "";
      srcLabel.textContent = (l.label || "Source") + (q ? " · " + q : "");
      srcDot.style.background = qDotColor(l);
    }
    function buildSrcMenu() {
      srcMenu.innerHTML = "";
      srcMenu.appendChild(el("div", "csp-src-head", "Select source"));
      if (!links.length) srcMenu.appendChild(el("div", "csp-src-head", "No sources"));
      links.forEach(function (l, i) {
        var q = l.quality && l.quality > 0 ? l.quality + "p" : "";
        var sub = [q, l.name].filter(Boolean).join(" · ");
        var b = el("button", "csp-sheet-item" + (i === linkIndex ? " csp-selected" : ""),
          '<span class="csp-qdot" style="background:' + qDotColor(l) + '"></span>' +
          "<span style='flex:1;min-width:0'>" + escapeHtml(l.label || "Source") +
          (sub ? "<small>" + escapeHtml(sub) + "</small>" : "") + "</span>" +
          (i === linkIndex ? ICONS.check : ""));
        b.onclick = function () {
          toggleSrcMenu(false);
          if (i !== linkIndex) { toast("Switching to " + (l.label || "source")); useLink(i, true); }
        };
        srcMenu.appendChild(b);
      });
    }
    function toggleSrcMenu(force) {
      srcMenuOpen = force == null ? !srcMenuOpen : !!force;
      if (srcMenuOpen) buildSrcMenu();
      srcMenu.classList.toggle("csp-on", srcMenuOpen);
      srcScrim.classList.toggle("csp-on", srcMenuOpen);
      srcChip.classList.toggle("csp-active", srcMenuOpen);
      if (srcMenuOpen) showControls();
    }
    srcChip.onclick = function () { if (!S.locked) toggleSrcMenu(); };
    srcScrim.addEventListener("click", function () { toggleSrcMenu(false); });

    var buildSheet = {
      speed: function (body) {
        SPEEDS.forEach(function (sp) {
          item(body, sp + "x", null, null, S.speedIdx === SPEEDS.indexOf(sp), function () {
            S.speedIdx = SPEEDS.indexOf(sp);
            video.playbackRate = sp;
            closeSheet();
          });
        });
      },
      quality: function (body) {
        if (S.hlsLevels.length > 1) {
          body.appendChild(el("div", "csp-sheet-section", "Quality"));
          item(body, "Auto", "adaptive bitrate", null, S.hlsLevelIdx === -1, function () {
            try { S.hls.currentLevel = -1; } catch (e) {}
            S.hlsLevelIdx = -1; closeSheet();
          });
          S.hlsLevels.forEach(function (lv) {
            item(body, lv.h ? lv.h + "p" : Math.round(lv.br / 1000) + " kbps", null, null, S.hlsLevelIdx === lv.i, function () {
              try { S.hls.currentLevel = lv.i; } catch (e) {}
              S.hlsLevelIdx = lv.i; closeSheet();
            });
          });
          body.appendChild(el("div", "csp-sheet-section", "Servers"));
        }
        if (!links.length) return;
        links.forEach(function (l, i) {
          var q = l.quality && l.quality > 0 ? l.quality + "p" : "";
          var sub = [q, l.name].filter(Boolean).join(" · ");
          var dotColor = l.quality >= 2160 ? "#E4D448" : l.quality >= 1080 ? "#48E484"
            : l.quality >= 720 ? "#3d50fa" : l.quality > 0 ? "#ea596e" : "#9ba0a4";
          var b = el("button", "csp-sheet-item" + (i === linkIndex ? " csp-selected" : ""),
            '<span class="csp-qdot" style="background:' + dotColor + '"></span>' +
            "<span style='flex:1;min-width:0'>" + escapeHtml(l.label || "Source") +
            (sub ? "<small>" + escapeHtml(sub) + "</small>" : "") + "</span>" +
            (i === linkIndex ? ICONS.check : ""));
          b.onclick = function () {
            if (i !== linkIndex) { toast("Switching to " + (l.label || "source")); useLink(i, true); }
            closeSheet();
          };
          body.appendChild(b);
        });
      },
      subs: function (body) {
        var subs = opts.subtitles || [];
        var sel = -1;
        item(body, "Off", null, null, subsTrackSelected() === false, function () {
          disableSubs(); S.subSel = -1; renderSubsMenu(); closeSheet();
        });
        subs.forEach(function (s, i) {
          item(body, s.name || ("Subtitle " + (i + 1)), s.lang || null, null, S.subSel === i, function () {
            enableSub(i); closeSheet();
          });
        });
        if (!subs.length) {
          var note = el("div", "csp-sheet-section", "No subtitles for this title");
          body.appendChild(note);
        }
        // subtitle size
        var sz = el("div", "csp-sub-offset");
        sz.appendChild(el("span", null, "Subtitle size"));
        var szb = el("div", "cso-btns");
        var cueClasses = ["csp-cue-s", "csp-cue-m", "csp-cue-l", "csp-cue-xl"];
        ["S", "M", "L", "XL"].forEach(function (lab, si) {
          var b = el("button", "csp-sz-btn" + (S.subScaleIdx === si ? " csp-sz-on" : ""), lab);
          b.onclick = function () {
            S.subScaleIdx = si;
            cueClasses.forEach(function (c) { root.classList.remove(c); });
            root.classList.add(cueClasses[si]);
            Array.prototype.forEach.call(szb.children, function (c, ci) {
              c.className = "csp-sz-btn" + (ci === si ? " csp-sz-on" : "");
            });
          };
          szb.appendChild(b);
        });
        sz.appendChild(szb);
        body.appendChild(sz);
        // offset control
        var off = el("div", "csp-sub-offset");
        off.appendChild(el("span", null, "Subtitle delay"));
        var btns = el("div", "cso-btns");
        var minus = el("button", null, "−0.5");
        var val = el("span", "cso-val", (S.subOffset >= 0 ? "+" : "") + S.subOffset.toFixed(1) + "s");
        var plus = el("button", null, "+0.5");
        function shift(d) {
          S.subOffset = Math.round((S.subOffset + d) * 10) / 10;
          val.textContent = (S.subOffset >= 0 ? "+" : "") + S.subOffset.toFixed(1) + "s";
          applySubOffset();
        }
        minus.onclick = function () { shift(-0.5); };
        plus.onclick = function () { shift(0.5); };
        btns.appendChild(minus); btns.appendChild(val); btns.appendChild(plus);
        off.appendChild(btns);
        body.appendChild(off);
      },
      audio: function (body) {
        var tracks = [];
        try {
          if (video.audioTracks && video.audioTracks.length) {
            for (var i = 0; i < video.audioTracks.length; i++) tracks.push(video.audioTracks[i]);
          }
        } catch (e) {}
        if (!tracks.length && S.hls && S.hls.audioTracks && S.hls.audioTracks.length > 1) {
          body.appendChild(el("div", "csp-sheet-section", "Audio track"));
          for (var a = 0; a < S.hls.audioTracks.length; a++) {
            (function (idx) {
              var t = S.hls.audioTracks[idx];
              item(body, t.name || t.lang || ("Audio " + (idx + 1)), t.lang || null, null, S.hls.audioTrack === idx, function () {
                S.hls.audioTrack = idx; closeSheet();
              });
            })(a);
          }
          return;
        }
        if (!tracks.length) {
          body.appendChild(el("div", "csp-sheet-section", "Default audio track"));
          item(body, links[linkIndex] ? (links[linkIndex].name + " · default") : "Default", null, null, true, function () { closeSheet(); });
          return;
        }
        tracks.forEach(function (t, i) {
          item(body, t.label || ("Audio " + (i + 1)), t.language || null, null, t.enabled, function () {
            for (var j = 0; j < tracks.length; j++) tracks[j].enabled = j === i;
            closeSheet();
          });
        });
      },
      eps: function (body) {
        var eps = opts.episodes || [];
        if (!eps.length) {
          body.appendChild(el("div", "csp-sheet-section", "No episode list"));
          return;
        }
        eps.forEach(function (e, i) {
          item(body, e.name || ("Episode " + (i + 1)), null, null, i === opts.episodeIndex, function () {
            closeSheet();
            if (opts.onEpisode && i !== opts.episodeIndex) opts.onEpisode(i);
          });
        });
      },
      more: function (body) {
        body.appendChild(el("div", "csp-sheet-section", "Player"));
        item(body, "Playback speed", SPEEDS[S.speedIdx] + "x", ICONS.speed, false, function () { openSheet("speed"); });
        item(body, "Video source", links[linkIndex] ? links[linkIndex].label : "", ICONS.hd, false, function () { openSheet("quality"); });
        item(body, fsActive() ? "Exit fullscreen" : "Fullscreen · landscape", null, ICONS.fs, false, function () {
          controller.toggleFullscreen(); closeSheet();
        });
        body.appendChild(el("div", "csp-sheet-section", "Buffering"));
        ["Small", "Medium", "Large"].forEach(function (blabel, bi) {
          item(body, blabel + " buffer", [15, 40, 90][bi] + "s ahead of playhead", null, S.bufferIdx === bi, function () {
            setBuffer(bi); closeSheet();
          });
        });
        body.appendChild(el("div", "csp-sheet-section", "Video"));
        item(body, "Picture-in-picture", null, ICONS.pip, false, function () {
          try {
            if (document.pictureInPictureElement) document.exitPictureInPicture();
            else video.requestPictureInPicture();
          } catch (e) { toast("PiP not supported"); }
          closeSheet();
        });
        item(body, "Loop " + (S.looping ? "on" : "off"), null, ICONS.loop, S.looping, function () {
          toggleLoop(); closeSheet();
        });
        item(body, "Locked " + (S.locked ? "on" : "off"), null, ICONS.lock, false, function () {
          toggleLock(); closeSheet();
        });
        body.appendChild(el("div", "csp-sheet-section", "Info"));
        var link = links[linkIndex];
        if (link) {
          var ahead = Math.max(0, (S.buffered || 0) - (video.currentTime || 0));
          var info = el("div", "csp-sheet-section", escapeHtml(link.label + "  ·  buffer " + ahead.toFixed(0) + "s  ·  HEVC " + (S.hevcOk ? "supported" : "not supported")));
          info.style.textTransform = "none"; info.style.letterSpacing = "0";
          body.appendChild(info);
        }
      },
    };
    function renderSubsMenu() { /* placeholder for refresh */ }
    function subsTrackSelected() {
      for (var i = 0; i < video.textTracks.length; i++) {
        if (video.textTracks[i].mode === "showing") return true;
      }
      return false;
    }
    function enableSub(i) {
      disableSubs();
      var s = (opts.subtitles || [])[i];
      if (!s) return;
      var url = s.url;
      signedUrl(url, function (signed) {
        if (/\.srt(\?|$)/i.test(url)) {
          fetch(signed)
            .then(function (r) { return r.text(); })
            .then(function (txt) {
              S.objectUrl = URL.createObjectURL(new Blob([srtToVtt(txt)], { type: "text/vtt" }));
              attachTrack(S.objectUrl, s.name || "Subtitle");
            }).catch(function () { toast("Subtitle fetch failed"); });
        } else {
          attachTrack(signed, s.name || "Subtitle");
        }
      });
      S.subSel = i;
    }
    function attachTrack(src, label) {
      var tr = el("track");
      tr.kind = "subtitles"; tr.label = label || "Subtitle"; tr.src = src;
      tr.default = true;
      video.appendChild(tr);
      setTimeout(function () {
        for (var i = 0; i < video.textTracks.length; i++) {
          video.textTracks[i].mode = i === video.textTracks.length - 1 ? "showing" : "disabled";
        }
      }, 60);
    }
    function disableSubs() {
      for (var i = 0; i < video.textTracks.length; i++) video.textTracks[i].mode = "disabled";
    }
    function applySubOffset() {
      // real cue re-scheduling: shift every cue of the showing track by the
      // delta between the newly chosen offset and the previously applied one
      var tt = null;
      for (var i = 0; i < video.textTracks.length; i++) {
        if (video.textTracks[i].mode === "showing") tt = video.textTracks[i];
      }
      if (!tt || !tt.cues) return;
      var prev = tt._cspOffset || 0;
      var d = S.subOffset - prev;
      if (!d) return;
      try {
        for (var j = 0; j < tt.cues.length; j++) {
          var c = tt.cues[j];
          c.startTime = Math.max(0, c.startTime + d);
          c.endTime = Math.max(0.05, c.endTime + d);
        }
        tt._cspOffset = S.subOffset;
      } catch (e) {}
    }
    function srtToVtt(srt) {
      var body = srt.replace(/\r+/g, "").replace(/^\uFEFF/, "")
        .replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, "$1.$2");
      return "WEBVTT\n\n" + body;
    }
    function setAspect(i) {
      S.aspectIdx = i;
      video.className = "csp-video " + ASPECTS[i].cls;
    }
    function setBuffer(i) {
      S.bufferIdx = i;
      if (S.hls) { try { S.hls.config.maxBufferLength = [15, 40, 90][i] || 40; } catch (e) {} }
      toast("Buffer: " + ["Small", "Medium", "Large"][i]);
    }
    function toggleLoop() {
      S.looping = !S.looping;
      loopBtn.classList.toggle("csp-active", S.looping);
      toast("Loop " + (S.looping ? "on" : "off"));
    }
    function toggleLock() {
      S.locked = !S.locked;
      root.classList.toggle("csp-locked", S.locked);
      lockBtn.innerHTML = S.locked ? ICONS.lock : ICONS.unlock;
      if (S.locked) {
        toast("Screen locked — tap lock icon to unlock");
        hideControls();
      } else showControls();
    }
    function renderQualityMenu() { /* refreshed on open */ }

    // mute button (desktop convenience; hidden on phones via CSS)
    var muteBtn = el("button", "csp-btn csp-optional", ICONS.vol);
    muteBtn.title = "Mute";
    btnrow.insertBefore(muteBtn, spacer);
    muteBtn.onclick = function () { video.muted = !video.muted; };

    // ------------------------------------------------------ button wiring
    function doSeekBy(d) {
      var t = Math.max(0, Math.min((video.currentTime || 0) + d, S.duration || 1e9));
      try { video.currentTime = t; } catch (e) {}
      S.position = t;
      renderTime();
    }
    // play/pause is ONLY reachable via double-tap (center) on touch —
    // the big center icon is just a visual indicator (tap-through), the
    // small bar button still works as an explicit control tap.
    playBig.style.pointerEvents = "none";
    playBtn.onclick = function () {
      if (isCoarse) { toast("Double-tap center screen to play/pause", 2400); showControls(); return; }
      togglePlay();
    };
    function togglePlay() {
      if (video.paused) {
        // CloudStream parity: resuming on a phone always returns to landscape
        if (isCoarse && !fsActive()) goFullscreenLandscape();
        var p = video.play(); if (p && p.catch) p.catch(function (e) { toast("Tap play again — browser blocked autoplay"); });
      } else video.pause();
    }
    rwBtn.onclick = function () { doSeekBy(-10); seekHint("left"); };
    ffBtn.onclick = function () { doSeekBy(10); seekHint("right"); };
    loopBtn.onclick = toggleLoop;
    speedBtn.onclick = function () { openSheet("speed"); };
    // fit/fill cycling lives on the bottom row (user spec — out of the menu)
    resizeBtn.onclick = function () {
      setAspect((S.aspectIdx + 1) % ASPECTS.length);
      toast("Screen: " + ASPECTS[S.aspectIdx].label);
    };
    qBtn.onclick = function () { openSheet("quality"); };
    ccBtn.onclick = function () { openSheet("subs"); };
    audioBtn.onclick = function () { openSheet("audio"); };
    epsBtn.onclick = function () { openSheet("eps"); };
    moreBtn.onclick = function () { openSheet("more"); };
    skipBtn.onclick = function () {
      var w = opts.skipIntro || [0, 85];
      doSeekBy(Math.max(w[1] + 1 - video.currentTime, 1));
      toast("Intro skipped");
    };
    lockBtn.onclick = toggleLock;
    backBtn.onclick = function () {
      if (opts.onBack) opts.onBack();
    };
    epsBtn.classList.toggle("csp-disabled", !(opts.episodes && opts.episodes.length));

    // seekbar drag
    var seeking = false;
    function seekFromEvent(e) {
      var rect = track.getBoundingClientRect();
      var x = (e.touches ? e.touches[0].clientX : e.clientX) - rect.left;
      var pct = Math.max(0, Math.min(1, x / rect.width));
      var t = pct * (S.duration || 0);
      playedBar.style.width = (pct * 100) + "%";
      thumb.style.left = (pct * 100) + "%";
      curEl.textContent = fmt(t);
      return t;
    }
    seekbar.addEventListener("pointerdown", function (e) {
      if (S.locked) return;
      seeking = true; seekbar.classList.add("csp-dragging");
      seekFromEvent(e);
      seekbar.setPointerCapture(e.pointerId);
    });
    seekbar.addEventListener("pointermove", function (e) { if (seeking) seekFromEvent(e); });
    seekbar.addEventListener("pointerup", function (e) {
      if (!seeking) return;
      var t = seekFromEvent(e);
      seeking = false; seekbar.classList.remove("csp-dragging");
      try { video.currentTime = t; } catch (err) {}
      S.position = t;
      showControls();
    });

    // ------------------------------------------------------- gestures
    var G = { active: false, sx: 0, sy: 0, t0: 0, mode: null, start: 0, startVol: 0, startBright: 50, moved: false };
    var brightness = 85;
    function gestureShow(iconSvg, value, frac) {
      gesture.classList.add("csp-on");
      gesture.querySelector("svg").outerHTML = iconSvg.replace("<svg", '<svg class="csp-g-icon"');
      gesture.querySelector(".csp-g-value").textContent = value;
      gesture.querySelector(".csp-g-bar i").style.width = Math.max(0, Math.min(100, frac)) + "%";
    }
    function gestureHide() { gesture.classList.remove("csp-on"); }
    function applyBrightness(v) {
      brightness = Math.max(0, Math.min(100, v));
      video.style.filter = brightness >= 100 ? "" : "brightness(" + (0.25 + 0.75 * brightness / 100) + ")";
      video.style.opacity = brightness < 8 ? "0.15" : "1";
    }
    root.addEventListener("touchstart", onTouchStart, { passive: false });
    root.addEventListener("touchmove", onTouchMove, { passive: false });
    root.addEventListener("touchend", onTouchEnd);
    root.addEventListener("mousedown", onDown);
    root.addEventListener("mouseup", onUp);

    function classify(e) {
      var rect = root.getBoundingClientRect();
      var x = e.clientX - rect.left, w = rect.width;
      return x < w * 0.36 ? "left" : x > w * 0.64 ? "right" : "mid";
    }
    function onTouchStart(e) {
      if (onUi(e)) return; // UI controls own their own taps/gestures
      if (S.locked || sheetOpen || seeking) return;
      if (e.touches.length !== 1) return;
      var t = e.touches[0];
      G.active = true; G.moved = false;
      G.sx = t.clientX; G.sy = t.clientY;
      G.t0 = Date.now();
      G.mode = classify(t);
      G.start = video.currentTime || 0;
      G.startVol = video.muted ? 0 : video.volume;
      S.longPressTimer = setTimeout(function () {
        if (!G.active) return;
        S.longPressActive = true;
        S.speedBeforePress = video.playbackRate;
        video.playbackRate = 2;
        speedChip.classList.add("csp-on");
      }, 520);
    }
    // NOTE: horizontal swipe-seek removed (user spec) — seeking is
    // double-tap left/right only. Vertical swipes keep brightness/volume.
    function onTouchMove(e) {
      if (!G.active || S.locked || seeking) return;
      var t = e.touches[0];
      var dx = t.clientX - G.sx, dy = t.clientY - G.sy;
      if (!G.moved && Math.abs(dx) < 14 && Math.abs(dy) < 14) return;
      if (S.longPressActive) { clearTimeout(S.longPressTimer); return; }
      clearTimeout(S.longPressTimer);
      G.moved = true;
      var rect = root.getBoundingClientRect();
      // vertical: left half brightness, right half volume
      e.preventDefault();
      var half = rect.width / 2;
      if (Math.abs(dy) > 8 && Math.abs(dy) >= Math.abs(dx) * 0.8) {
        var frac = dy / rect.height;
        if (G.mode === "left" || (G.mode === "mid" && t.clientX < half)) {
          var b = brightness - frac * 120;
          applyBrightness(b);
          gestureShow(ICONS.sun, Math.round(brightness) + "%", brightness);
        } else {
          var v = Math.max(0, Math.min(1, G.startVol - frac * 1.5));
          video.volume = v; video.muted = false;
          gestureShow(video.volume === 0 ? ICONS.volx : ICONS.vol, Math.round(v * 100) + "%", v * 100);
        }
      }
    }
    // ------------------------------------------------ double-tap model
    // single tap  (any side)        -> show/hide controls (after a short
    //                                  window that lets a 2nd tap arrive)
    // double tap  left | right      -> -10s | +10s
    // double tap  center            -> play / pause
    // NOTHING toggles playback on a single touch.
    var pendingTap = null;
    var tapHintShown = false;
    function cancelPendingTap() {
      if (pendingTap) { clearTimeout(pendingTap.timer); pendingTap = null; }
    }
    function onTouchEnd(e) {
      clearTimeout(S.longPressTimer);
      lastTouchTs = Date.now();
      if (S.longPressActive) {
        S.longPressActive = false;
        video.playbackRate = S.speedBeforePress;
        speedChip.classList.remove("csp-on");
      }
      gestureHide();
      if (!G.active) return;
      G.active = false;
      var dt = Date.now() - G.t0;
      if (!G.moved && dt < 300 && !S.locked && !sheetOpen) {
        var side = classify(e.changedTouches ? e.changedTouches[0] : e);
        showControls();
        if (pendingTap && pendingTap.side === side && Date.now() - pendingTap.t < 420) {
          // --- second tap: the ONLY path to seek / play-pause
          clearTimeout(pendingTap.timer); pendingTap = null;
          if (side === "left") { doSeekBy(-10); seekHint("left"); ping(); }
          else if (side === "right") { doSeekBy(10); seekHint("right"); ping(); }
          else { ping(); togglePlay(); }
        } else {
          // --- first tap: wait ~400ms for a possible double tap
          cancelPendingTap();
          var firedSide = side;
          var tt = setTimeout(function () {
            pendingTap = null;
            if (S.destroyed) return;
            // one-time retry: re-request auto fullscreen if the earlier
            // attempt was rejected (expired activation)
            if (isCoarse && firedSide === "mid" && !S.fsAutoDone && !video.paused) {
              S.fsAutoDone = true;
              goFullscreenLandscape(function (ok) { if (!ok) S.fsAutoDone = false; });
            }
            // NOTE: no toggle here! Controls were already shown by the tap;
            // toggling again hid them 400ms later ("instant hide" bug).
            // They now stay up for the full 15s per user spec.
            if (!tapHintShown) {
              tapHintShown = true;
              toast("Double-tap sides = seek 10s · center = play/pause", 3400);
            }
          }, 400);
          pendingTap = { side: firedSide, t: Date.now(), timer: tt };
        }
        return;
      }
      showControls();
    }
    // mouse long-press (desktop) = 2x
    function onDown(e) {
      if (e.button !== 0 || onUi(e) || S.locked || sheetOpen) return;
      S.longPressTimer = setTimeout(function () {
        S.longPressActive = true;
        S.speedBeforePress = video.playbackRate;
        video.playbackRate = 2;
        speedChip.classList.add("csp-on");
      }, 520);
    }
    function onUp() {
      clearTimeout(S.longPressTimer);
      if (S.longPressActive) {
        S.longPressActive = false;
        video.playbackRate = S.speedBeforePress;
        speedChip.classList.remove("csp-on");
      }
    }
    root.addEventListener("dblclick", function (e) {
      // desktop mouse only — touch double taps are handled by onTouchEnd
      // (browsers can synthesize dblclick from taps; suppress those)
      if (Date.now() - lastTouchTs < 800) return;
      var side = classify(e);
      if (side === "left") { doSeekBy(-10); seekHint("left"); }
      else if (side === "right") { doSeekBy(10); seekHint("right"); }
      else controller.toggleFullscreen();
    });

    // ---------------------------------------------------------- keyboard
    function onKey(e) {
      if (S.destroyed) return;
      switch (e.key) {
        case " ": case "k": e.preventDefault(); togglePlay(); break;
        case "ArrowLeft": doSeekBy(-10); seekHint("left"); break;
        case "ArrowRight": doSeekBy(10); seekHint("right"); break;
        case "j": doSeekBy(-10); break;
        case "l": doSeekBy(10); break;
        case "ArrowUp": e.preventDefault(); video.muted = false; video.volume = Math.min(1, video.volume + .08); gestureShow(ICONS.vol, Math.round(video.volume * 100) + "%", video.volume * 100); setTimeout(gestureHide, 700); break;
        case "ArrowDown": e.preventDefault(); video.volume = Math.max(0, video.volume - .08); gestureShow(ICONS.vol, Math.round(video.volume * 100) + "%", video.volume * 100); setTimeout(gestureHide, 700); break;
        case "m": video.muted = !video.muted; break;
        case "f": controller.toggleFullscreen(); break;
        case "Escape": if (!document.fullscreenElement && opts.onBack) opts.onBack(); break;
      }
      showControls();
    }
    document.addEventListener("keydown", onKey);

    // --------------------------------------------------------- fullscreen
    controller.toggleFullscreen = function () {
      try {
        if (fsActive()) exitFullscreenQuiet();
        else goFullscreenLandscape();
      } catch (e) { toast("Fullscreen not supported"); }
    };

    // ----------------------------------------------------------- controls
    controller.play = function () { video.play().catch(function () {}); };
    controller.pause = function () { video.pause(); };
    controller.setLinkIndex = function (i) { useLink(i, true); };
    controller.getLinkIndex = function () { return linkIndex; };
    controller.setPosition = function (t) { try { video.currentTime = t; } catch (e) {} };
    controller.showControls = showControls;
    controller.videoEl = function () { return video; };

    controller.destroy = function () {
      S.destroyed = true;
      stopProgressSave();
      saveProgress(false);
      clearLoadTimers();
      if (S.retryTimer) { clearTimeout(S.retryTimer); S.retryTimer = 0; }
      if (S.stallTimer) { clearInterval(S.stallTimer); S.stallTimer = 0; }
      teardownEngine();
      // NOTE: fullscreen/orientation are intentionally left alone here —
      // destroy() also runs on retry + episode switch. The host app owns
      // fullscreen exit via its unmount cleanup.
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("fullscreenchange", onFsChange);
      document.removeEventListener("webkitfullscreenchange", onFsChange);
      root.classList.remove("csp-root");
      root.innerHTML = "";
    };

    // ------------------------------------------------------------ boot
    setPlayIcon();
    renderTime();
    applyBrightness(100);
    root.classList.add("csp-cue-m");
    if (links.length) useLink(linkIndex, false);
    else showError("No video sources were found");
    if (opts.subtitles && opts.subtitles.length) ccBtn.classList.add("csp-active");

    return controller;
  }

  window.SkStreamPlayer = {
    mount: mount,
    version: VERSION,
    hevcSupported: hevcSupported,
  };
})();
