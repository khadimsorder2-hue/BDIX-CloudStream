package com.redowan

import android.content.Context
import android.content.SharedPreferences

/**
 * Local per-source usage tracker + speed classifier.
 *
 * Answers the user's "kon source e koto view / fast konta" request:
 *  - every host that successfully yields a playable link bumps a persistent
 *    view counter (SharedPreferences, survives restarts);
 *  - each source label carries a speed class derived from the host type
 *    (direct CDNs stream fastest on BDIX/BD networks) and the view count,
 *    e.g. "GDx R2 720p [Fast | 4 views]" so the user can pick the best one;
 *  - counters are per-host, not per-quality, and only increment when a link
 *    was actually extracted (deduped inside one loadLinks call).
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
     * Fast  = direct CDN / object storage / premium mirrors (r2, vircloud, FSL, S3, 10Gbps…)
     * Med   = resolver chains & file hosts (gofile, multicloud, hubcloud, pixeldrain…)
     */
    fun speed(host: String): String {
        val h = host.lowercase()
        return when {
            listOf(
                "r2.dev", "cloudflarestorage", "fastcdn", "vircloud",
                "gdx r2", "gdxfiles", "gdflix r2", "super fast",
                "fsl", "s3", "10gbps", "direct download", "download file",
                "movielinkbd", "direct dl",
            ).any { it in h } -> "Fast"
            listOf(
                "pixeldrain", "gofile", "multicloud", "hubcloud", "hubdrive",
                "gdflix", "filepress", "filebee", "filesdl", "shikshakdaak",
                "m4ulinks", "driveseed", "driveleech", "buzz", "mega", "watch",
            ).any { it in h } -> "Med"
            else -> ""
        }
    }

    /**
     * Append speed + usage info to a source label:
     * "GDx R2 720p" -> "GDx R2 720p [Fast | 4 views]".
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

    /**
     * Normalise a URL into a stable host key used for counters/speed:
     * the label-ish host token (e.g. "gdx r2", "hubcloud buzz", "vircloud").
     */
    fun hostKeyOf(link: String): String {
        val l = link.lowercase()
        return when {
            "pixeldrain" in l -> "pixeldrain"
            "gofile" in l -> "gofile"
            "gdxfiles" in l -> "gdx r2"
            "r2.dev" in l || "cloudflarestorage" in l -> "r2 direct"
            "fastcdn" in l -> "fastcdn"
            "multicloud" in l || "multidownload" in l -> "multicloud"
            "hubdrive" in l || "hubcloud" in l -> "hubcloud"
            "gdflix" in l -> "gdflix"
            "filepress" in l || "filebee" in l -> "filepress"
            "filesdl" in l -> "filesdl"
            "shikshakdaak" in l -> "shikshakdaak"
            "m4ulinks" in l -> "m4ulinks"
            "driveseed" in l || "driveleech" in l -> "driveseed"
            "vircloud" in l -> "vircloud"
            "savelinks" in l -> "savelinks"
            else -> link.substringBefore("/").substringAfter("//").take(24)
        }
    }
}
