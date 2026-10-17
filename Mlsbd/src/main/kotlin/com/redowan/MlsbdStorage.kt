package com.redowan

import android.content.Context
import android.content.SharedPreferences

/**
 * Tiny SharedPreferences wrapper that persists the Cloudflare `cf_clearance`
 * cookie MLSBD hands out after a successful WebView challenge.
 *
 * The cookie is bound to the User-Agent + IP that solved the challenge, so it
 * is only reusable when the WebView UA is reused for subsequent requests
 * (see [MlsbdProvider]).
 */
object MlsbdStorage {

    private const val PREFS = "MlsbdPrefs"
    private const val KEY_CF = "cf_clearance"
    private const val KEY_CF_TS = "cf_clearance_timestamp"

    /** cf_clearance lives ~1 year, but is IP-bound; treat stale after 30 days. */
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, 0)
        }
    }

    private fun p(): SharedPreferences? {
        if (prefs == null) {
            MlsbdProvider.appContext?.let { init(it) }
        }
        return prefs
    }

    fun saveCfCookie(cookie: String) {
        val e = p()?.edit() ?: return
        e.putString(KEY_CF, cookie)
        e.putLong(KEY_CF_TS, System.currentTimeMillis())
        e.apply()
    }

    /** (cookie value or null, saved timestamp in ms). */
    fun getCfCookie(): Pair<String?, Long> {
        val p = p() ?: return null to 0L
        return p.getString(KEY_CF, null) to p.getLong(KEY_CF_TS, 0L)
    }

    fun clearCfCookie() {
        val e = p()?.edit() ?: return
        e.remove(KEY_CF)
        e.remove(KEY_CF_TS)
        e.apply()
    }

    fun isFresh(): Boolean {
        val (_, ts) = getCfCookie()
        return ts > 0 && System.currentTimeMillis() - ts < MAX_AGE_MS
    }
}
