import { NextRequest, NextResponse } from "next/server";
import { createServerClient } from "@supabase/ssr";
import { supabaseUrl, supabaseAnonKey, supabaseConfigured } from "@/lib/supabase/config";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// OAuth callback: exchanges the ?code= for a session and sets cookies.
export async function GET(req: NextRequest) {
  const { searchParams, origin } = new URL(req.url);
  const code = searchParams.get("code");
  const next = searchParams.get("next") || "/";

  if (!supabaseConfigured()) return NextResponse.redirect(`${origin}/`);
  if (!code) return NextResponse.redirect(`${origin}/`);

  // collect cookies set during the exchange, then attach to ONE final response
  const cookieJar: { name: string; value: string; options?: Record<string, unknown> }[] = [];
  const sb = createServerClient(supabaseUrl(), supabaseAnonKey(), {
    cookies: {
      getAll() {
        return req.cookies.getAll().map(({ name, value }) => ({ name, value }));
      },
      setAll(cookiesToSet) {
        cookieJar.push(...cookiesToSet);
      },
    },
  });

  try {
    await sb.auth.exchangeCodeForSession(code);
  } catch (e) {
    // exchange failed — surface via hash for debugging, but land on the app
    return NextResponse.redirect(`${origin}/?auth_error=${encodeURIComponent(String(e).slice(0, 80))}`);
  }

  const response = NextResponse.redirect(`${origin}${next}`);
  for (const { name, value, options } of cookieJar) {
    response.cookies.set(name, value, options as never);
  }
  return response;
}
