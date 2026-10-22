// Supabase env config — the app degrades gracefully when keys are absent
// (shows a setup screen instead of crashing).

export function supabaseUrl(): string {
  return process.env.NEXT_PUBLIC_SUPABASE_URL || "";
}

export function supabaseAnonKey(): string {
  return process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || "";
}

/** true when both public env vars are present (client + server safe) */
export function supabaseConfigured(): boolean {
  return !!(supabaseUrl() && supabaseAnonKey());
}

/** shared secret for signing proxy stream URLs (hotlink protection) */
export function proxySecret(): string {
  return process.env.PROXY_SECRET || "skstream-dev-proxy-secret";
}

/** link-cache worker (Cloudflare) */
export function workerUrl(): string {
  return process.env.WORKER_URL || "";
}
export function workerSecret(): string {
  return process.env.WORKER_SECRET || "";
}
/** secret the cron trigger must present to /api/warm */
export function cronSecret(): string {
  return process.env.CRON_SECRET || "skstream-dev-cron-secret";
}
