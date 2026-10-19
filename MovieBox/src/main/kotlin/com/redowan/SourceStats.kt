package com.redowan

import android.content.Context
import android.content.SharedPreferences

/**
 * Local per-source usage tracker + speed classifier (same pattern as the
 * Mlsbd / MovieLinkBD modules). For MovieBox every stream resolves through
 * the aoneroom API to direct hakunaymatata CDN links, so those count as
 * "Fast"; the label shows "MovieBox · Hindi dub · 720p [Fast | 4 views]".
 */
object SourceStats {

    private const val PREFS = "SourceStatsPrefs"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, 0)
        }
    }

    private fun p(): SharedPreferences? = prefs

    /** Bump the view counter for a host key. */
    fun record(host: String) {
        val key = host.lowercase().trim()
        if (key.isEmpty()) return
        val p = p() ?: return
        p.edit().putInt(key, (p.getInt(key, 0) + 1)).apply()
    }

    fun views(host: String): Int {
        return p()?.getInt(host.lowercase().trim(), 0) ?: 0
    }

    /**
     * Speed class by host type.
     * Fast  = direct CDN / object storage (hakunaymatata bcdn/sbcdn/cacdn, r2…)
     * Med   = resolver chains & file hosts
     */
    fun speed(host: String): String {
        val h = host.lowercase()
        return when {
            listOf(
                "hakunaymatata", "bcdn", "sbcdn", "cacdn", "moviebox api",
                "r2.dev", "cloudflarestorage", "vircloud", "direct download",
            ).any { it in h } -> "Fast"
            listOf(
                "pixeldrain", "gofile", "multicloud", "hubcloud", "macdn", "aoneroom",
            ).any { it in h } -> "Med"
            else -> ""
        }
    }

    /**
     * Append speed + usage info to a source label:
     * "MovieBox · Hindi dub · 720p" -> "MovieBox · Hindi dub · 720p [Fast | 4 views]".
     */
    fun decorate(host: String, baseLabel: String): String {
        val s = speed(host)
        val v = views(host)
        val parts = mutableListOf<String>()
        if (s.isNotEmpty()) parts.add(s)
        if (v > 0) parts.add("$v views")
        if (parts.isEmpty()) return baseLabel
        return "$baseLabel [${parts.joinToString(" | ")}]"
    }
}
