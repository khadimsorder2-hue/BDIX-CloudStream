// CloudStream-parity landscape helpers.
// Android Chrome requires fullscreen before screen.orientation.lock(),
// and both must be called inside a live user gesture. The Play button
// gesture is the only reliable entry point, so ResultScreen calls
// enterLandscapeNow() synchronously in its onClick chain — loading and
// playback then run inside an already-landscape fullscreen document.
// PlayerOverlay's unmount cleanup calls exitLandscapeNow().

type FsElement = HTMLHtmlElement & {
  webkitRequestFullscreen?: () => Promise<void> | void;
};
type LockableOrientation = ScreenOrientation & {
  lock?: (o: string) => Promise<void>;
  unlock?: () => void;
};

export function isLandscapeTarget(): boolean {
  try {
    return !!window.matchMedia?.("(pointer: coarse)")?.matches;
  } catch {
    return false;
  }
}

/** Enter app fullscreen + lock landscape. Must run inside a user gesture. */
export function enterLandscapeNow(): void {
  try {
    if (!isLandscapeTarget()) return; // desktop: manual fullscreen only
    const doc = document as Document & { webkitFullscreenElement?: Element | null };
    const el = document.documentElement as FsElement;
    const lock = () => {
      try {
        (screen.orientation as LockableOrientation).lock?.("landscape")?.catch?.(() => {});
      } catch { /* unsupported */ }
    };
    if (document.fullscreenElement || doc.webkitFullscreenElement) {
      lock();
      return;
    }
    const req = el.requestFullscreen?.bind(el) || el.webkitRequestFullscreen?.bind(el);
    if (!req) { lock(); return; }
    const p = req() as Promise<void> | undefined;
    if (p && typeof p.then === "function") p.then(lock).catch(() => {});
    else lock();
  } catch { /* never block playback on fullscreen failures */ }
}

/** Exit fullscreen + release the orientation lock. */
export function exitLandscapeNow(): void {
  try {
    const doc = document as Document & { webkitFullscreenElement?: Element | null; webkitExitFullscreen?: () => void };
    if (document.fullscreenElement || doc.webkitFullscreenElement) {
      const p = document.exitFullscreen?.() as Promise<void> | undefined;
      if (p && typeof p.catch === "function") p.catch(() => {});
      doc.webkitExitFullscreen?.();
    }
    (screen.orientation as LockableOrientation)?.unlock?.();
  } catch { /* noop */ }
}
