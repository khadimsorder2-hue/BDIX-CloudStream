package com.redowan

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.FormBody
import org.json.JSONObject
import java.net.URI
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

internal fun qLabel(q: Int): String = if (q > 0) "${q}p " else ""

private fun cleanUrl(u: String): String = u.replace("&amp;", "&").trim()

private fun baseUrlOf(url: String): String {
    return runCatching {
        URI(url).let { "${it.scheme}://${it.host}" }
    }.getOrDefault("")
}

/** Qualities like "1080p"/"4K" from a filename or header, else Unknown. */
internal fun indexQuality(str: String?): Int {
    return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

/**
 * Strips release noise and keeps meaningful tags — used to build compact
 * HubCloud labels like "[WEB-DL H265 DDP5.1 1.2GB]" (CNCVerse cleanTitle).
 */
private fun cleanTitle(title: String): String {
    val name = title.replace(Regex("\\.[a-zA-Z0-9]{2,4}$"), "")
    val normalized = name
        .replace(Regex("WEB[-_. ]?DL", RegexOption.IGNORE_CASE), "WEB-DL")
        .replace(Regex("WEB[-_. ]?RIP", RegexOption.IGNORE_CASE), "WEBRIP")
        .replace(Regex("H[ .]?265", RegexOption.IGNORE_CASE), "H265")
        .replace(Regex("H[ .]?264", RegexOption.IGNORE_CASE), "H264")
        .replace(Regex("DDP[ .]?([0-9]\\.[0-9])", RegexOption.IGNORE_CASE), "DDP$1")
    val parts = normalized.split(" ", "_", ".")
    val sourceTags = setOf("WEB-DL", "WEBRIP", "BLURAY", "HDRIP", "DVDRIP", "HDTV", "CAM", "TS", "BRRIP", "BDRIP")
    val codecTags = setOf("H264", "H265", "X264", "X265", "HEVC", "AVC")
    val audioTags = setOf("AAC", "AC3", "DTS", "MP3", "FLAC", "DD", "DDP", "EAC3")
    val audioExtras = setOf("ATMOS")
    val hdrTags = setOf("SDR", "HDR", "HDR10", "HDR10+", "DV", "DOLBYVISION")
    val filtered = parts.mapNotNull { part ->
        val p = part.uppercase()
        when {
            p in sourceTags -> p
            p in codecTags -> p
            audioTags.any { p.startsWith(it) } -> p
            p in audioExtras -> p
            p in hdrTags -> if (p == "DV" || p == "DOLBYVISION") "DOLBYVISION" else p
            p == "NF" || p == "CR" -> p
            else -> null
        }
    }
    return filtered.distinct().joinToString(" ")
}

/** Decorated emission used by every extractor: keeps SourceStats labels. */
internal suspend fun emitDecorated(
    callback: (ExtractorLink) -> Unit,
    hostKey: String,
    source: String,
    baseLabel: String,
    url: String,
    quality: Int,
    referer: String,
) {
    emitLink(callback, source, SourceStats.decorate(hostKey, baseLabel), url, quality, referer)
}

/**
 * GDFlix clones — ported from CNCVerse with dynamic domain resolution
 * (SaurabhKaperwan/Utils urls.json) plus the proven direct regexes:
 *   - "CLOUD DOWNLOAD [R2]" button decodes to pub-*.r2.dev?token=… (206 video/mkv, verified)
 *   - raw pub-*.r2.dev links on the page (fastcdn proxy fallback)
 * Other servers: DIRECT DL, pixeldrain, Index Links, DriveBot, GoFile —
 * each guarded so dead ones (drivebot DNS, goflix HTML) fail silently.
 */
object GDFlix : ExtractorApi() {
    override val name: String = "GDFlix"
    override val mainUrl: String = "https://gdflix.dev"
    override val requiresReferer = false

    private suspend fun getLatestUrl(): String {
        return try {
            val u = JSONObject(
                app.get(
                    "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json",
                    timeout = 20
                ).text
            ).optString("gdflix")
            if (u.isNullOrEmpty()) mainUrl else u
        } catch (e: Exception) {
            mainUrl
        }
    }

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
            val fileName = doc.select("ul > li.list-group-item:contains(Name)").text()
                .substringAfter("Name : ").trim().ifBlank {
                    doc.title().substringAfter("|").trim()
                }
            val fileSize = doc.select("ul > li.list-group-item:contains(Size)").text()
                .substringAfter("Size : ").trim()
            val q = mlsbdQualityNum("$fileName ${html.take(8000)}")
            val sizeTag = if (fileSize.isNotBlank()) "[$fileSize]" else ""
            val relTag = cleanTitle(fileName).take(40)

            fun base(): String = "$relTag${if (relTag.isNotBlank()) " " else ""}$sizeTag"

            for (a in doc.select("div.text-center a[href], a.btn[href]")) {
                val text = a.text().trim()
                val link = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
                if (link.isEmpty() || !link.startsWith("http")) continue
                when {
                    text.contains("DIRECT DL", true) -> emitDecorated(
                        callback, "gdflix", "GDFlix[Direct]",
                        "GDFlix[Direct] ${base()}".trim(), link, q, pageUrl
                    )
                    text.contains("CLOUD DOWNLOAD [R2]", true) || "url=" in link && "fastcdn" in link -> {
                        val target = URLDecoder.decode(link.substringAfter("url=", link), "UTF-8")
                        if (target.startsWith("http")) emitDecorated(
                            callback, "gdflix r2", "GDFlix R2",
                            "GDFlix R2 ${base()}".trim(), target, q, pageUrl
                        )
                    }
                    text.contains("pixeldra", true) || text.contains("pixel", true) -> {
                        val b = baseUrlOf(link)
                        val final = if (link.contains("download", true)) link
                        else "$b/api/file/${link.substringAfterLast("/")}?download"
                        emitDecorated(
                            callback, "pixeldrain", "GDFlix Pixeldrain",
                            "GDFlix Pixeldrain ${base()}".trim(), final, q, "https://pixeldrain.com/"
                        )
                    }
                    text.contains("Index Links", true) -> {
                        try {
                            val latest = getLatestUrl()
                            val idxUrl = if (link.startsWith("/")) latest + link else link
                            val idx = app.get(idxUrl, referer = pageUrl, timeout = 45)
                            if (idx.isSuccessful) {
                                for (btn in idx.document.select("a.btn.btn-outline-info")) {
                                    val serverUrl = btn.attr("abs:href").ifBlank {
                                        latest + btn.attr("href")
                                    }
                                    if (serverUrl.startsWith("http")) emitDecorated(
                                        callback, "gdflix", "GDFlix[Index]",
                                        "GDFlix[Index] ${base()}".trim(), serverUrl, q, idxUrl
                                    )
                                }
                            }
                        } catch (e: Exception) { /* skip */ }
                    }
                    text.contains("DRIVEBOT", true) -> {
                        try {
                            val id = link.substringAfter("id=").substringBefore("&")
                            val doId = link.substringAfter("do=").substringBefore("==")
                            for (b in listOf("https://drivebot.sbs", "https://indexbot.site")) {
                                val botLink = "$b/download?id=$id&do=$doId"
                                val bot = app.get(botLink, timeout = 60)
                                if (!bot.isSuccessful) continue
                                val cookies = bot.cookies["PHPSESSID"] ?: ""
                                val botDoc = bot.text
                                val token = Regex("""formData\.append\('token', '([a-f0-9]+)'\)""")
                                    .find(botDoc)?.groupValues?.get(1) ?: continue
                                val postId = Regex("""fetch\('/download\?id=([a-zA-Z0-9/+]+)'""")
                                    .find(botDoc)?.groupValues?.get(1) ?: continue
                                val dl = app.post(
                                    "$b/download?id=$postId",
                                    requestBody = FormBody.Builder().add("token", token).build(),
                                    headers = mapOf("Referer" to botLink),
                                    cookies = mapOf("PHPSESSID" to cookies),
                                    timeout = 60
                                ).text
                                val out = Regex("url\":\"(.*?)\"").find(dl)
                                    ?.groupValues?.get(1)?.replace("\\", "") ?: continue
                                if (out.startsWith("http")) emitDecorated(
                                    callback, "gdflix", "GDFlix[DriveBot]",
                                    "GDFlix[DriveBot] ${base()}".trim(), out, q, b
                                )
                            }
                        } catch (e: Exception) { /* skip */ }
                    }
                    text.contains("GoFile", true) -> {
                        try {
                            Gofile.getUrl(link, pageUrl, subtitleCallback, callback)
                        } catch (e: Exception) { /* skip */ }
                    }
                    "t.me" in link || "telegram" in link -> { /* skip */ }
                }
            }

            // Proven raw regexes: direct R2 object + fastcdn proxy.
            Regex("https://pub-[A-Za-z0-9-]+\\.r2\\.dev/[^\"'<>\\s]+").findAll(html)
                .map { cleanUrl(it.value) }.distinct().take(2)
                .forEach {
                    emitDecorated(callback, "r2 direct", "GDFlix R2", "GDFlix R2 ${base()}".trim(), it, q, pageUrl)
                }
            Regex("https://fastcdn-dl\\.pages\\.dev/\\?url=[^\"'<>\\s]+", RegexOption.IGNORE_CASE).findAll(html)
                .map { cleanUrl(it.value) }.distinct().take(1)
                .forEach {
                    emitDecorated(callback, "fastcdn", "FastCDN", "FastCDN ${base()}".trim(), it, q, pageUrl)
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
                    // Only emit REAL file locations, never the HTML page URL.
                    val valid = !loc.isNullOrBlank() && loc.startsWith("http") &&
                        listOf("r2.dev", "cloudflarestorage.com", "pixeldrain").any { it in loc }
                    if (valid) {
                        emitDecorated(callback, "gdx r2", "GDx R2", "GDxFiles R2", loc, q, "$mainUrl/")
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
                                emitDecorated(
                                    callback, "pixeldrain", "Pixeldrain", "GDxFiles Pixeldrain",
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
                        emitDecorated(callback, "gdx r2", "GDx R2", "GDxFiles R2", it, q, pageUrl)
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

            // The view page links its ArtPlayer as player.php/?v=<code>
            // (note the slash before the query — the old regex missed it).
            val playerLink = Regex(
                "https?://[a-z0-9.-]*multicloudlinks\\.com/player\\.php/?\\?v=[A-Za-z0-9]+",
                RegexOption.IGNORE_CASE
            ).find(html)?.value
            if (playerLink != null) {
                try {
                    val pres = app.get(playerLink, referer = pageUrl, timeout = 45)
                    val phtml = pres.text.replace("&amp;", "&")
                    val src = Regex("streamSrc\\s*=\\s*\"([^\"]+)\"").find(phtml)?.groupValues?.get(1)
                        ?: Regex("\"(https?://[^\"]*multidownload\\.lol[^\"]*)\"").find(phtml)?.groupValues?.get(1)
                    if (src != null) {
                        emitDecorated(
                            callback, "multicloud", "MultiCloud Stream",
                            "MultiCloud Stream", cleanUrl(src), q, playerLink
                        )
                    }
                } catch (e: Exception) {}
            }

            // Fallback: the /d/ route on the view page with &action=download.
            Regex("https://dr\\d*\\.multidownload\\.lol/d/[^\"'<>\\s]+", RegexOption.IGNORE_CASE).findAll(html)
                .map { cleanUrl(it.value) }.distinct().take(1)
                .forEach {
                    val dl = if ("action=" in it) it else "$it&action=download"
                    emitDecorated(
                        callback, "multicloud", "MultiCloud Direct",
                        "MultiCloud Direct", dl, q, pageUrl
                    )
                }
        } catch (e: Exception) {
            // silent
        }
    }
}

/**
 * Shared HubCloud page parser (CNCVerse port — the drive page lists server
 * buttons whose label identifies the mirror). Also used by Shikshakdaak,
 * which renders the identical layout on its own domain.
 */
internal suspend fun extractHubCloudPage(
    rawUrl: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
    sourceName: String,
    hostKey: String,
) {
    try {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return
        val realUrl = uri.toString()
        val origin = "${uri.scheme}://${uri.host}"

        // Landing page may wrap the real drive page behind #download.
        val href = runCatching {
            if ("hubcloud.php" in realUrl || sourceName != "Hub-Cloud") realUrl
            else {
                val doc = app.get(realUrl, referer = referer, timeout = 45).document
                val raw = doc.selectFirst("#download")?.attr("href")
                    ?: doc.select("a[href]").firstOrNull { el ->
                        val h = el.attr("href")
                        h.contains("hubcloud.php") || h.contains("gamerxyt.com")
                    }?.attr("href") ?: ""
                when {
                    raw.startsWith("http", true) -> raw
                    raw.isNotEmpty() -> origin.trimEnd('/') + "/" + raw.trimStart('/')
                    else -> ""
                }
            }
        }.getOrDefault("")

        if (href.isBlank()) return

        val document = app.get(href, referer = referer, timeout = 60)
        if (!document.isSuccessful) return
        val pageUrl = document.url
        val html = document.text.replace("&amp;", "&")
        val size = document.document.selectFirst("i#size")?.text().orEmpty()
        val header = document.document.selectFirst("div.card-header")?.text().orEmpty()

        val headerDetails = cleanTitle(header)
        val q = mlsbdQualityNum("$header $headerDetails ${html.take(6000)}")
        val labelExtras = buildString {
            if (headerDetails.isNotEmpty()) append("[$headerDetails]")
            if (size.isNotEmpty()) append("[$size]")
        }

        val seen = mutableSetOf<String>()
        for (element in document.document.select("a[href], a[hx-redirect], button[hx-redirect]")) {
            val hx = element.attr("hx-redirect")
            val link0 = element.attr("abs:href").ifBlank { element.attr("href") }
            var link = when {
                hx.startsWith("http") -> hx
                link0.startsWith("http") -> link0
                else -> continue
            }
            if (!seen.add(link)) continue
            val text = (element.ownText() + " " + element.text() + " " +
                (element.parent()?.text() ?: "")).lowercase()

            when {
                "fsl server" in text || "fslv2" in text -> emitDecorated(
                    callback, hostKey, sourceName, "$sourceName [FSL] $labelExtras", link, q, pageUrl
                )
                "download file" in text -> emitDecorated(
                    callback, hostKey, sourceName, "$sourceName $labelExtras", link, q, pageUrl
                )
                "buzzserver" in text || "buzz" in text -> {
                    try {
                        val resp = app.get("$link/download", referer = link, allowRedirects = false)
                        val dlink = resp.headers["hx-redirect"]
                            ?: resp.headers["HX-Redirect"].orEmpty()
                        if (dlink.isNotBlank()) emitDecorated(
                            callback, hostKey, sourceName, "$sourceName [BuzzServer] $labelExtras", dlink, q, link
                        )
                    } catch (e: Exception) { /* skip */ }
                }
                "pixeldra" in text || "pixel server" in text || "pixel" in text -> {
                    val b = baseUrlOf(link)
                    val finalUrl = if ("download" in link) link
                    else "$b/api/file/${link.substringAfterLast("/")}?download"
                    emitDecorated(
                        callback, "pixeldrain", "$sourceName Pixeldrain",
                        "$sourceName Pixeldrain $labelExtras", finalUrl, q, "https://pixeldrain.com/"
                    )
                }
                "s3 server" in text || "s3" in text -> emitDecorated(
                    callback, hostKey, sourceName, "$sourceName [S3 Server] $labelExtras", link, q, pageUrl
                )
                "mega server" in text || "mega" in text -> emitDecorated(
                    callback, hostKey, sourceName, "$sourceName [Mega Server] $labelExtras", link, q, pageUrl
                )
                "gofile" in text -> {
                    try { Gofile.getUrl(link, pageUrl, subtitleCallback, callback) }
                    catch (e: Exception) { /* skip */ }
                }
                "10gbps" in text || "gpdl" in link -> emitDecorated(
                    callback, hostKey, sourceName, "$sourceName [10Gbps] $labelExtras", link, q, pageUrl
                )
                "telegram" in text || "t.me" in link -> { /* skip */ }
                else -> {
                    // Unknown button — only trust direct file-ish targets.
                    if (Regex("\\.(mkv|mp4|avi)(\\?|$)").containsMatchIn(link)) {
                        emitDecorated(
                            callback, hostKey, sourceName, "$sourceName $labelExtras", link, q, pageUrl
                        )
                    }
                }
            }
        }
    } catch (e: Exception) {
        // silent
    }
}

/** HubCloud (hubcloud.foo) — see [extractHubCloudPage]. */
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
        extractHubCloudPage(url, referer, subtitleCallback, callback, name, "hubcloud")
    }
}

/** Shikshakdaak renders the HubCloud layout on shikshakdaak.com (CNCVerse). */
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
        extractHubCloudPage(url, referer, subtitleCallback, callback, name, "shikshakdaak")
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
                emitDecorated(callback, "filepress", "FilePress", "FilePress", d, q, pageUrl)
            }
            if (direct.isNotEmpty()) return

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
                                emitDecorated(callback, "filepress", "FilePress", "FilePress", it, q, dlHref)
                            }
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            // silent
        }
    }
}

/** Gofile — proper API resolution (CNCVerse: account token + web token). */
object Gofile : ExtractorApi() {
    override val name: String = "Gofile"
    override val mainUrl: String = "https://gofile.io"
    override val requiresReferer = false
    private val mainApi = "https://api.gofile.io"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)").find(url)?.groupValues?.get(1) ?: return
            val token = JSONObject(app.post("$mainApi/accounts").text)
                .getJSONObject("data").getString("token")

            val globalJs = app.get("$mainUrl/dist/js/global.js", timeout = 30).text
            val wt = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""")
                .find(globalJs)?.groupValues?.getOrNull(1) ?: return

            val contentText = app.get(
                "$mainApi/contents/$id?wt=$wt",
                headers = mapOf("Authorization" to "Bearer $token")
            ).text
            val data = JSONObject(contentText).getJSONObject("data")
            val children = data.getJSONObject("children")
            val firstFileId = children.keys().asSequence().first()
            val fileObj = children.getJSONObject(firstFileId)

            val link = fileObj.getString("link")
            val fileName = fileObj.optString("name", "Gofile")
            val fileSize = fileObj.optLong("size", 0L)
            val sizeFormatted = if (fileSize <= 0) ""
            else if (fileSize < 1024L * 1024 * 1024) "%.2f MB".format(fileSize / 1024.0 / 1024)
            else "%.2f GB".format(fileSize / 1024.0 / 1024 / 1024)
            val sizeTag = if (sizeFormatted.isNotBlank()) "[$sizeFormatted]" else ""
            val q = mlsbdQualityNum(fileName)

            // Single emission carrying the accountToken cookie (CNCVerse
            // behaviour) plus the decorated SourceStats label.
            callback(
                newExtractorLink("Gofile", SourceStats.decorate("gofile", "Gofile $sizeTag"), link) {
                    this.quality = q
                    this.referer = mainUrl
                    this.headers = mapOf("Cookie" to "accountToken=$token")
                }
            )
        } catch (e: Exception) {
            // silent
        }
    }
}

/**
 * Filesdl clones (new6.filesdl.site) — CNCVerse port: the container lists
 * buttons for Hubcloud / GDFLIX / Gofile / Direct Download / Fast Cloud.
 */
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
            val titleText = res.document.select("div.title").text()
            val quality = Regex("(\\d{3,4}p)", RegexOption.IGNORE_CASE).find(titleText)?.value ?: "Unknown"
            val q = mlsbdQualityNum(titleText)

            for (a in res.document.select("div.container a")) {
                val source = a.text()
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                when {
                    source.contains("Hubcloud", true) ->
                        HubCloud.getUrl(href, "Filesdl", subtitleCallback, callback)
                    source.contains("GDFLIX", true) ->
                        GDFlix.getUrl(href, "Filesdl", subtitleCallback, callback)
                    source.contains("Gofile", true) ->
                        Gofile.getUrl(href, "Filesdl", subtitleCallback, callback)
                    source.contains("Direct Download", true) ||
                        source.contains("Ultra FastDL", true) ||
                        source.contains("Fast Cloud-02", true) -> {
                        try {
                            val r = app.get(href, allowRedirects = false, timeout = 30)
                            val redirect = r.headers["location"] ?: href
                            emitDecorated(
                                callback, "filesdl", "Filesdl",
                                "Filesdl [$quality] [Direct]", redirect, q, "Filesdl"
                            )
                        } catch (e: Exception) { /* skip */ }
                    }
                    source.contains("Fast Cloud", true) -> emitDecorated(
                        callback, "filesdl", "Filesdl", "Filesdl [$quality] [Fast Cloud]", href, q, "Filesdl"
                    )
                }
            }
        } catch (e: Exception) {
            // silent
        }
    }
}

/** M4ulinks (m4ulinks.com) — CNCVerse port: buttons resolve via registry. */
object M4ulinks : ExtractorApi() {
    override val name: String = "M4ulinks"
    override val mainUrl: String = "https://m4ulinks.com"
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
            for (a in res.document.select("div.downloads-btns-div a")) {
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                if (!href.startsWith("http")) continue
                try {
                    com.lagradost.cloudstream3.utils.loadExtractor(href, url, subtitleCallback, callback)
                } catch (e: Exception) { /* skip */ }
            }
        } catch (e: Exception) {
            // silent
        }
    }
}
