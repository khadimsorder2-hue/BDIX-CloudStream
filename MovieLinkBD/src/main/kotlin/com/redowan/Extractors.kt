package com.redowan

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

private fun qLabel(q: Int): String = if (q > 0) "${q}p " else ""

private fun cleanUrl(u: String): String = u.replace("&amp;", "&").trim()

private fun emitLink(
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

/** Host-based dispatch used by MovieLinkBDProvider.loadLinks(). */
internal object MlbdExtractors {
    suspend fun resolve(
        link: String,
        providerName: String,
        providerBase: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val l = link.lowercase()
        when {
            "pixeldrain.com" in l -> {
                val id = link.substringAfter("pixeldrain.com/").trimStart('/')
                    .substringBefore("?").substringBefore("/").trim()
                if (id.isNotBlank() && !id.startsWith("api")) {
                    emitLink(
                        callback, "Pixeldrain", "Pixeldrain",
                        "https://pixeldrain.com/api/file/$id?download", 0, "https://pixeldrain.com/"
                    )
                } else {
                    emitLink(callback, "Pixeldrain", "Pixeldrain", link, 0, "https://pixeldrain.com/")
                }
            }
            "hubcloud" in l || "hubdrive" in l -> HubCloud.getUrl(link, providerBase, subtitleCallback, callback)
            "gdflix" in l -> GDFlix.getUrl(link, providerBase, subtitleCallback, callback)
            "gdxfiles" in l -> GDxFiles.getUrl(link, providerBase, subtitleCallback, callback)
            "multicloud" in l -> MultiCloudLinks.getUrl(link, providerBase, subtitleCallback, callback)
            "filepress" in l || "filebee" in l -> FilePress.getUrl(link, providerBase, subtitleCallback, callback)
            "gofile" in l -> Gofile.getUrl(link, providerBase, subtitleCallback, callback)
            "filesdl" in l -> Filesdl.getUrl(link, providerBase, subtitleCallback, callback)
            "driveseed" in l || "driveleech" in l -> DriveSeed.getUrl(link, providerBase, subtitleCallback, callback)
            else -> emitLink(callback, providerName, "$providerName Direct", link, mlbdQualityNum(link), providerBase + "/")
        }
    }
}

/** GDFlix clones — direct mirrors scraped from the file page. */
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
            val q = mlbdQualityNum("$filename ${html.take(8000)}")

            Regex("https://pub-[A-Za-z0-9-]+\\.r2\\.dev/[^\"'<>\\s]+").findAll(html)
                .map { cleanUrl(it.value) }.distinct().take(2)
                .forEach { emitLink(callback, "GDFlix R2", "${qLabel(q)}GDFlix R2", it, q, pageUrl) }
            Regex("https://fastcdn-dl\\.pages\\.dev/\\?url=[^\"'<>\\s]+").findAll(html)
                .map { cleanUrl(it.value) }.distinct().take(1)
                .forEach { emitLink(callback, "FastCDN", "${qLabel(q)}GDFlix FastCDN", it, q, pageUrl) }
            Regex("https://instant\\.busycdn\\.xyz/[A-Za-z0-9]+").findAll(html)
                .map { it.value }.distinct().take(1)
                .forEach { emitLink(callback, "BusyCDN", "${qLabel(q)}GDFlix BusyCDN", it, q, pageUrl) }
            Regex("https://[a-z0-9.-]*indexserver\\.site/[^\"'<>\\s]+").findAll(html)
                .map { cleanUrl(it.value) }.distinct().take(1)
                .forEach { emitLink(callback, "IndexServer", "${qLabel(q)}GDFlix IndexServer", it, q, pageUrl) }
            Regex("https://drivebot\\.sbs/download\\?id=[^\"'<>\\s]+").findAll(html)
                .map { cleanUrl(it.value) }.distinct().take(1)
                .forEach { emitLink(callback, "DriveBot", "${qLabel(q)}GDFlix DriveBot", it, q, pageUrl) }
        } catch (e: Exception) {
            // silent
        }
    }
}

/** GDxFiles (gdxfiles.com) — signed R2 route. */
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
            val q = mlbdQualityNum(html.take(15000))
            var emitted = 0

            if (!hash.isNullOrBlank()) {
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
            }
            if (emitted == 0) {
                Regex("https://pub-[A-Za-z0-9-]+\\.r2\\.dev/[^\"'<>\\s]+").findAll(html)
                    .map { cleanUrl(it.value) }.distinct().take(2)
                    .forEach { emitLink(callback, "GDx R2", "${qLabel(q)}GDxFiles R2", it, q, pageUrl) }
            }
        } catch (e: Exception) {
            // silent
        }
    }
}

/** MultiCloud view pages -> ArtPlayer streamSrc + direct /d/ links. */
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
            val q = mlbdQualityNum(html.take(15000))

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

/** HubCloud — server buttons (FSL/Buzz/Pixeldrain/S3/10Gbps/Mega/Gofile). */
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
            val q = mlbdQualityNum("$headerInfo ${html.take(6000)}")
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
                    "s3" in text -> emitLink(callback, "HubCloud S3", "${qLabel(q)}HubCloud S3", target, q, pageUrl)
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

/** Driveseed/Driveleech clones — direct mirrors on the file page. */
object DriveSeed : ExtractorApi() {
    override val name: String = "DriveSeed"
    override val mainUrl: String = "https://driveseed.org"
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
            val q = mlbdQualityNum(html.take(15000))
            Regex(
                "https?://[^\"'<>\\s]*(?:r2\\.dev|busycdn\\.xyz|cloudflarestorage\\.com|pixeldrain\\.com|\\.mp4|\\.mkv)[^\"'<>\\s]*"
            ).findAll(html).map { cleanUrl(it.value) }.distinct().take(3)
                .forEach { emitLink(callback, "DriveSeed", "DriveSeed" + qLabel(q), it, q, pageUrl) }
        } catch (e: Exception) {
            // silent
        }
    }
}

/** FilePress clones — best effort (often Cloudflare-gated). */
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
            val q = mlbdQualityNum(html.take(15000))
            Regex(
                "https?://[^\"'<>\\s]*(?:r2\\.dev|busycdn\\.xyz|cloudflarestorage\\.com|multidownload\\.lol|pixeldrain\\.com)/[^\"'<>\\s]*"
            ).findAll(html).map { cleanUrl(it.value) }.distinct().take(3)
                .forEach { emitLink(callback, "FilePress", "FilePress" + qLabel(q), it, q, pageUrl) }
        } catch (e: Exception) {
            // silent
        }
    }
}

/** Gofile — best effort API resolution. */
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
            val q = mlbdQualityNum(html.take(15000))
            Regex(
                "https?://[^\"'<>\\s]*(?:r2\\.dev|busycdn\\.xyz|cloudflarestorage\\.com|multidownload\\.lol|pixeldrain\\.com|\\.mp4|\\.mkv)[^\"'<>\\s]*"
            ).findAll(html).map { cleanUrl(it.value) }.distinct().take(3)
                .forEach { emitLink(callback, "Filesdl", "Filesdl" + qLabel(q), it, q, pageUrl) }
        } catch (e: Exception) {
            // silent
        }
    }
}
