// Server-side Supabase admin client (service_role) — NEVER import client-side.
import { createClient } from "@supabase/supabase-js";
import type { SupabaseClient } from "@supabase/supabase-js";
import { supabaseUrl } from "@/lib/supabase/config";

let svc: SupabaseClient | null = null;

export function getServiceClient(): SupabaseClient | null {
  const key = process.env.SUPABASE_SERVICE_KEY || "";
  if (!supabaseUrl() || !key) return null;
  if (!svc) svc = createClient(supabaseUrl(), key, { auth: { persistSession: false } });
  return svc;
}

/** the only admin account (owner) */
export const ADMIN_EMAIL = process.env.ADMIN_EMAIL || "yitube4k@gmail.com";

export async function isAdminUser(userId: string, email: string | undefined): Promise<boolean> {
  if (email && email.toLowerCase() === ADMIN_EMAIL.toLowerCase()) return true;
  const s = getServiceClient();
  if (!s) return false;
  const { data } = await s.from("profiles").select("is_admin").eq("id", userId).maybeSingle();
  return !!((data as { is_admin?: boolean } | null)?.is_admin);
}
