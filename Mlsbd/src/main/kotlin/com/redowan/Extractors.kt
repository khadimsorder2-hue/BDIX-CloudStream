package com.redowan

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

/** Extracts "720p"/"1080"/"4K" style quality tokens into an Int (0 = unknown). */
internal fun mlsbdQualityNum(text: String): Int {
    if (text.isBlank()) return 0
    val m = Regex("(?i)\\b(2160|1440|1080|720|576|480|360|240)\\s*p?\\b|\\b4k\\b").find(text)
        ?: return 0
    val t = m.value.lowercase()
    return when {
        t == "4k" -> 2160
        t.endsWith("p") -> t.dropLast(1).toIntOrNull() ?: 0
        else -> t.toIntOrNull() ?: 0
    }
}

internal suspend fun emitLink(
    callback: (ExtractorLink) -> Unit,
    source: String,
    label: String,
    url: String,
    quality: Int,
    referer: String,
    type: ExtractorLinkType = ExtractorLinkType.VIDEO,
) {
    callback(
        newExtractorLink(source, label, url, type) {
            this.quality = quality
            this.referer = referer
        }
    )
}

private fun qLabel(q: Int): String = if (q > 0) "${q}p " else ""

private fun cleanUrl(u: String): String = u.replace("&amp;", "&").trim()

/**
 * GDFlix clones (gdflix.dev redirects to newN.gdflix.io).
 * File pages carry a set of mirrors: direct pub-*.r2.dev?token= object
 * storage, fastcdn proxy, instant.busycdn, indexserver, drivebot and
 * goflix.sbs mirror links — all of them serve raw video bytes.
 */
object GDFlix : ExtractorApi() {
    override val name: String = "GDFlix"
    override val mainUrl: String = "https://gdflix.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, referer = referer, timeout = 60)
            if (!res.isSuccessful) return
            val html = res.text.replace("&amp;", "&")
            val pageUrl = res.url
            val doc = res.document
            val filename = doc.title().substringAfter("|").trim().ifBlank { "GDFlix" }
            val q = mlsbdQualityNum(filename + " " + html.take(8000))
            var emitted = 0

            // 1) Direct Cloudflare R2 object storage (proven video bytes).
            Regex("https://pub-[A-Za-z0-9-]+\\.r2\\.dev/[^\"'<>\\s]+").findAll(html)
                .map { cleanUrl(it.value) }.distinct().take(2)
                .forEach {
                    emitLink(callback, "GDFlix R2", "${qLabel(q)}GDFlix R2", it, q, pageUrl)
                    emitted++
                }

            // 2) fastcdn proxy of the same object.
            Regex("https://fastcdn-dl\\.pages\\.dev/\\?url=[^\"'<>\\s]+").findAll(html)
                .map { cleanUrl(it.value) }.distinct().take(1)
                .forEach {
                    emitLink(callback, "FastCDN", "${qLabel(q)}GDFlix FastCDN", it, q, pageUrl)
                    emitted++
                }

            // 3) instant CDN mirror.
            Regex("https://instant\\.busycdn\\.xyz/[A-Za-z0-9]+").findAll(html)
                .map { it.value }.distinct().take(1)
                .forEach {
                    emitLink(callback, "BusyCDN", "${qLabel(q)}GDFlix BusyCDN", it, q, pageUrl)
                    emitted++
                }

            // 4) indexserver mirror (base64-wrapped stream).
            Regex("https://[a-z0-9.-]*indexserver\\.site/[^\"'<>\\s]+").findAll(html)
                .map { cleanUrl(it.value) }.distinct().take(1)
                .forEach {
                    emitLink(callback, "IndexServer", "${qLabel(q)}GDFlix IndexServer", it, q, pageUrl)
                    emitted++
                }

            // 5) drivebot mirror.
            Regex("https://drivebot\\.sbs/download\\?id=[^\"'<>\\s]+").findAll(html)
                .map { cleanUrl(it.value) }.distinct().take(1)
                .forEach {
                    emitLink(callback, "DriveBot", "${qLabel(q)}GDFlix DriveBot", it, q, pageUrl)
                    emitted++
                }

            // 6) goflix mirror page.
            if (emitted == 0) {
                Regex("https://goflix\\.sbs/en/mirror/[A-Za-z0-9]+").findAll(html)
                    .map { it.value }.distinct().take(1)
                    .forEach {
                        emitLink(callback, "Goflix", "${qLabel(q)}GDFlix Goflix", it, q, pageUrl)
                        emitted++
                    }
            }
        } catch (e: Exception) {
            // silent
        }
    }
}

/**
 * GDxFiles (gdxfiles.com). The file page exposes per-server download routes:
 *   /file/r2/<code>/download?hash=…        -> 302 to a signed cloudflarestorage URL
 *   /file/pixeldrain/<code>/download?hash=… -> 302 to pixeldrain
 * The signed R2 Location is the cleanest direct link available.
 */
object GDxFiles : ExtractorApi() {
    override val name: String = "GDxFiles"
    override val mainUrl: String = "https://gdxfiles.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, referer = referer, timeout = 45)
            if (!res.isSuccessful) return
            val pageUrl = res.url
            val html = res.text.replace("&amp;", "&")
            val code = Regex("/file/([A-Za-z0-9]{4,})").find(pageUrl)?.groupValues?.get(1)
                ?: Regex("/file/([A-Za-z0-9]{4,})").find(url)?.groupValues?.get(1)
                ?: return
            val hash = Regex("[?&]hash=([a-f0-9]{8,})").find(pageUrl)?.groupValues?.get(1)
                ?: Regex("[?&]hash=([a-f0-9]{8,})").find(html)?.groupValues?.get(1)
            val q = mlsbdQualityNum(html.take(15000))
            var emitted = 0

            if (!hash.isNullOrBlank()) {
                // Signed R2 direct download (24h signed URL, real filename).
                try {
                    val r2 = app.get(
                        "$mainUrl/file/r2/$code/download?hash=$hash",
                        referer = pageUrl, timeout = 45, allowRedirects = false
                    )
                    val loc = r2.headers["location"]
                    if (!loc.isNullOrBlank() && loc.startsWith("http")) {
                        emitLink(callback, "GDx R2", "${qLabel(q)}GDxFiles R2", loc, q, "$mainUrl/")
                        emitted++
                    }
                } catch (e: Exception) {}

                // Pixeldrain mirror route.
                if (emitted == 0) {
                    try {
                        val px = app.get(
                            "$mainUrl/file/pixeldrain/$code/download?hash=$hash",
                            referer = pageUrl, timeout = 45, allowRedirects = false
                        )
                        val loc = px.headers["location"]
                        if (!loc.isNullOrBlank() && loc.contains("pixeldrain")) {
                            val id = loc.substringAfter("file/").substringBefore("?").trim('/')
                            if (id.isNotBlank()) {
                                emitLink(
                                    callback, "Pixeldrain", "${qLabel(q)}GDxFiles Pixeldrain",
                                    "https://pixeldrain.com/api/file/$id?download", q, "https://pixeldrain.com/"
                                )
                                emitted++
                            }
                        }
                    } catch (e: Exception) {}
                }
            }

            // Any raw R2 links rendered on the page.
            if (emitted == 0) {
                Regex("https://pub-[A-Za-z0-9-]+\\.r2\\.dev/[^\"'<>\\s]+").findAll(html)
                    .map { cleanUrl(it.value) }.distinct().take(2)
                    .forEach {
                        emitLink(callback, "GDx R2", "${qLabel(q)}GDxFiles R2", it, q, pageUrl)
                        emitted++
                    }
            }
        } catch (e: Exception) {
            // silent
        }
    }
}

/**
 * MultiCloud links (newN.multicloudlinks.com). The view page embeds a
 * player.php?v=<code> ArtPlayer whose streamSrc is a multidownload.lol
 * watch URL, plus tokenised /d/ download links.
 */
object MultiCloudLinks : ExtractorApi() {
    override val name: String = "MultiCloud"
    override val mainUrl: String = "https://new2.multicloudlinks.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, referer = referer, timeout = 45)
            if (!res.isSuccessful) return
            val html = res.text.replace("&amp;", "&")
            val pageUrl = res.url
            val q = mlsbdQualityNum(html.take(15000))

            val playerLink = Regex(
                "https?://[a-z0-9.-]*multicloudlinks\\.com/player\\.php\\?v=[A-Za-z0-9]+",
                RegexOption.IGNORE_CASE
            ).find(html)?.value
            if (playerLink != null) {
                try {
                    val pres = app.get(playerLink, referer = pageUrl, timeout = 45)
                    val src = Regex("(?:const|let|var)\\s+streamSrc\\s*=\\s*[\"']([^\"']+)[\"']")
                        .find(pres.text)?.groupValues?.get(1)
                        ?: Regex("[\"'](https?://[^\"']*multidownload\\.lol[^\"']*)[\"']")
                            .find(pres.text)?.groupValues?.get(1)
                    if (src != null) {
                        emitLink(
                            callback, "MultiCloud Watch", "MultiCloud Watch" + qLabel(q),
                            cleanUrl(src), q, playerLink
                        )
                    }
                } catch (e: Exception) {}
            }

            Regex("https://dr\\d*\\.multidownload\\.lol/d/[^\"'<>\\s]+").findAll(html)
                .map { cleanUrl(it.value) }.distinct().take(1)
                .forEach {
                    emitLink(callback, "MultiCloud Direct", "MultiCloud Direct" + qLabel(q), it, q, pageUrl)
                }
        } catch (e: Exception) {
            // silent
        }
    }
}

/**
 * HubCloud (hubcloud.foo / hubcloud.php?url=…). The drive page lists server
 * buttons; the label tells which mirror each one is. BuzzServer links are
 * delivered through an htmx `hx-redirect` attribute instead of href.
 */
object HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.foo"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            var link = url
            if (link.contains("hubcloud.php")) {
                val u = Regex("[?&]url=([^&]+)").find(link)?.groupValues?.get(1)
                if (u != null) link = URLDecoder.decode(u, "UTF-8")
            }
            val res = app.get(link, referer = referer, timeout = 60)
            if (!res.isSuccessful) return
            val html = res.text.replace("&amp;", "&")
            val pageUrl = res.url
            val doc = res.document
            val headerInfo = doc.select("div.card-header").text().take(300)
            val q = mlsbdQualityNum("$headerInfo ${html.take(6000)}")
            val seen = mutableSetOf<String>()

            for (a in doc.select("a[href], a[hx-redirect], button[hx-redirect]")) {
                val hx = a.attr("hx-redirect")
                val href = a.attr("abs:href").ifBlank { "" }
                val target = when {
                    hx.startsWith("http") -> hx
                    href.startsWith("http") -> href
                    else -> continue
                }
                if (!seen.add(target)) continue
                val text = (a.text() + " " + (a.parent()?.text() ?: "")).lowercase()
                when {
                    "pixeldrain" in text || "pixel server" in text -> {
                        val id = target.substringAfter("pixeldrain.com/").trimStart('/')
                            .substringBefore("?").trim('/')
                        if (id.isNotBlank() && !id.startsWith("api")) {
                            emitLink(
                                callback, "HubCloud Pixeldrain", "${qLabel(q)}HubCloud Pixeldrain",
                                "https://pixeldrain.com/api/file/$id?download", q, "https://pixeldrain.com/"
                            )
                        } else {
                            emitLink(callback, "HubCloud Pixeldrain", "${qLabel(q)}HubCloud Pixeldrain", target, q, pageUrl)
                        }
                    }
                    "buzz" in text -> emitLink(callback, "HubCloud BuzzServer", "${qLabel(q)}HubCloud Buzz", target, q, pageUrl)
                    "fsl" in text -> emitLink(callback, "HubCloud FSL", "${qLabel(q)}HubCloud FSL", target, q, pageUrl)
                    "s3 server" in text || "s3" in text -> emitLink(callback, "HubCloud S3", "${qLabel(q)}HubCloud S3", target, q, pageUrl)
                    "10gbps" in text || "gpdl" in text -> emitLink(callback, "HubCloud 10Gbps", "${qLabel(q)}HubCloud 10Gbps", target, q, pageUrl)
                    "mega" in text -> emitLink(callback, "HubCloud Mega", "${qLabel(q)}HubCloud Mega", target, q, pageUrl)
                    "gofile" in text -> Gofile.getUrl(target, referer, subtitleCallback, callback)
                    "telegram" in text || "t.me" in text -> { /* skip */ }
                    "download" in text -> emitLink(callback, "HubCloud Download", "${qLabel(q)}HubCloud Download", target, q, pageUrl)
                }
            }
        } catch (e: Exception) {
            // silent
        }
    }
}

/**
 * FilePress clones (newN.filepress.lat). Often Cloudflare-gated; when the
 * page loads it exposes direct file links or a /down/ route that does.
 */
object FilePress : ExtractorApi() {
    override val name: String = "FilePress"
    override val mainUrl: String = "https://new1.filepress.lat"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, referer = referer, timeout = 45)
            if (!res.isSuccessful) return
            val html = res.text.replace("&amp;", "&")
            val pageUrl = res.url
            val q = mlsbdQualityNum(html.take(15000))

            val direct = Regex(
                "https?://[^\"'<>\\s]*(?:r2\\.dev|busycdn\\.xyz|cloudflarestorage\\.com|multidownload\\.lol|pixeldrain\\.com)/[^\"'<>\\s]*"
            ).findAll(html).map { cleanUrl(it.value) }.distinct().toList()
            for (d in direct.take(3)) {
                emitLink(callback, "FilePress", "FilePress" + qLabel(q), d, q, pageUrl)
            }
            if (direct.isNotEmpty()) return

            // Secondary download route on the page.
            val dlHref = Regex("[\"'](https?://[^\"']*filepress[^\"']*/down[^\"']*)[\"']")
                .find(html)?.groupValues?.get(1)
            if (dlHref != null) {
                try {
                    val res2 = app.get(dlHref, referer = pageUrl, timeout = 45)
                    if (res2.isSuccessful) {
                        Regex(
                            "https?://[^\"'<>\\s]*(?:r2\\.dev|busycdn\\.xyz|cloudflarestorage\\.com|multidownload\\.lol)/[^\"'<>\\s]*"
                        ).findAll(res2.text.replace("&amp;", "&")).map { cleanUrl(it.value) }
                            .distinct().take(2)
                            .forEach {
                                emitLink(callback, "FilePress", "FilePress" + qLabel(q), it, q, dlHref)
                            }
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            // silent
        }
    }
}

/** Gofile — best effort API resolution (needs a public website token). */
object Gofile : ExtractorApi() {
    override val name: String = "Gofile"
    override val mainUrl: String = "https://gofile.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = Regex("gofile\\.io/(?:[a-z]{2}/)?(?:d/|c/|download/)?([A-Za-z0-9]{6,})")
                .find(url)?.groupValues?.get(1) ?: return
            val servers = app.get("https://api.gofile.io/servers", timeout = 30).text
            val server = Regex("\"name\"\\s*:\\s*\"([a-z0-9-]+)\"").findAll(servers)
                .firstOrNull()?.groupValues?.get(1) ?: return
            val content = app.get(
                "https://$server.gofile.io/contents/$id",
                headers = mapOf("Authorization" to "Bearer $server"), timeout = 30
            ).text
            Regex("\"link\"\\s*:\\s*\"(https?://[^\"]+)\"").find(content)?.groupValues?.get(1)
                ?.let { emitLink(callback, "Gofile", "Gofile", it, 0, "$mainUrl/") }
        } catch (e: Exception) {
            // silent
        }
    }
}

/** Filesdl clones — generic direct-link scraping. */
object Filesdl : ExtractorApi() {
    override val name: String = "Filesdl"
    override val mainUrl: String = "https://new6.filesdl.site"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, referer = referer, timeout = 45)
            if (!res.isSuccessful) return
            val html = res.text.replace("&amp;", "&")
            val pageUrl = res.url
            val q = mlsbdQualityNum(html.take(15000))
            Regex(
                "https?://[^\"'<>\\s]*(?:r2\\.dev|busycdn\\.xyz|cloudflarestorage\\.com|multidownload\\.lol|pixeldrain\\.com|\\.mp4|\\.mkv)[^\"'<>\\s]*"
            ).findAll(html).map { cleanUrl(it.value) }.distinct().take(3)
                .forEach { emitLink(callback, "Filesdl", "Filesdl" + qLabel(q), it, q, pageUrl) }
        } catch (e: Exception) {
            // silent
        }
    }
}

/** Shikshakdaak — generic direct-link scraping. */
object Shikshakdaak : ExtractorApi() {
    override val name: String = "Shikshakdaak"
    override val mainUrl: String = "https://shikshakdaak.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, referer = referer, timeout = 45)
            if (!res.isSuccessful) return
            val html = res.text.replace("&amp;", "&")
            val pageUrl = res.url
            val q = mlsbdQualityNum(html.take(15000))
            Regex(
                "https?://[^\"'<>\\s]*(?:r2\\.dev|busycdn\\.xyz|cloudflarestorage\\.com|multidownload\\.lol|\\.mp4|\\.mkv)[^\"'<>\\s]*"
            ).findAll(html).map { cleanUrl(it.value) }.distinct().take(3)
                .forEach { emitLink(callback, "Shikshakdaak", "Shikshakdaak" + qLabel(q), it, q, pageUrl) }
        } catch (e: Exception) {
            // silent
        }
    }
}
