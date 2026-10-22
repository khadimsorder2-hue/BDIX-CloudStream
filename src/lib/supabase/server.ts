import { createServerClient } from "@supabase/ssr";
import type { SupabaseClient, User } from "@supabase/supabase-js";
import type { NextRequest } from "next/server";
import { supabaseUrl, supabaseAnonKey, supabaseConfigured } from "./config";

/**
 * Route-handler Supabase client (read-only cookies — enough for auth checks;
 * the browser client owns token refreshes).
 * Returns null when env vars are missing (setup not done yet).
 */
export function getSupabaseRoute(req: NextRequest): SupabaseClient | null {
  if (!supabaseConfigured()) return null;
  return createServerClient(supabaseUrl(), supabaseAnonKey(), {
    cookies: {
      getAll() {
        return req.cookies.getAll().map(({ name, value }) => ({ name, value }));
      },
      setAll() {
        /* read-only in route handlers */
      },
    },
  });
}

/**
 * Returns the signed-in user for a route-handler request, or null.
 * Reads the supabase auth cookies (sb-*-auth-token) sent by the web app.
 */
export async function getAuthUser(req: NextRequest): Promise<User | null> {
  const sb = getSupabaseRoute(req);
  if (!sb) return null;
  try {
    const { data } = await sb.auth.getUser();
    return data?.user ?? null;
  } catch {
    return null;
  }
}
