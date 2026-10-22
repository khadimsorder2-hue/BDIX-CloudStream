import { NextRequest, NextResponse } from "next/server";
import { getAuthUser } from "@/lib/supabase/server";
import { supabaseConfigured } from "@/lib/supabase/config";
import type { User } from "@supabase/supabase-js";

/**
 * Gate for streaming APIs: once Supabase is configured, a signed-in account
 * is REQUIRED (user rule: "account chara kono kichu stream hobi nah").
 * Before setup is done (no env keys yet) requests pass so the app stays
 * testable — configuration flips the gate on.
 */
export async function requireUser(
  req: NextRequest
): Promise<{ user: User } | { error: NextResponse }> {
  if (!supabaseConfigured()) {
    // setup mode — allow through
    return { user: { id: "setup-mode", email: "" } as unknown as User };
  }
  const user = await getAuthUser(req);
  if (!user) {
    return {
      error: NextResponse.json(
        { error: "auth required", links: [], items: [] },
        { status: 401 }
      ),
    };
  }
  return { user };
}
