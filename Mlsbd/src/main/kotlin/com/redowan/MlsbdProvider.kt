package com.redowan

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * MLSBD (mlsbd.co — "The Largest Movie Link Store of Bangladesh").
 *
 * Extraction ported 1:1 from the CNCVerse MlsbdProvider (user-verified
 * working), reduced to plain HTTP — no WebView / Cloudflare machinery,
 * because mlsbd.co serves plain 200s to a desktop Chrome UA (verified
 * live 2026-10, 32 cards on the homepage):
 *
 *  - Catalog cards: div.single-post (.post-title, .thumb > a, .thumb img).
 *  - Post pages: .name title, img.aligncenter poster, rich plot blocks.
 *  - Download sections: div.post-section-title.download; consecutive <p>
 *    siblings hold a.sd / a.hd / a.hevc anchors pointing at
 *    savelinks.me/view/<code> (one per quality: 480p/720p/1080p).
 *  - loadLinks: fetch the savelinks view page, take <ul><li><a href=http>
 *    and hand every file host (gdxfiles / gdflix / multicloudlinks /
 *    filepress …) to the dedicated extractors in Extractors.kt.
 *
 * Extra (user request): every source label carries a speed class and a
 * persistent local view counter via SourceStats — "GDx R2 720p [Fast | 4
 * views]" — so the user can see which source is fast and which one they
 * actually use most.
 */
class MlsbdProvider : MainAPI() {

    override var mainUrl = "https://mlsbd.co"
    override var name = "MLSBD"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.AnimeMovie)

    companion object {
        /** Desktop UA — plain requests pass, no challenge (verified 2026-10). */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
    }

    private val headers = mapOf(
        "User-Agent" to DESKTOP_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "bn,en-US;q=0.9,en;q=0.8",
    )

    /** Latest on the homepage, then the priority sections (CNCVerse order). */
    override val mainPage = mainPageOf(
        "" to "Latest Movies",
        "category/bangla-dubbed" to "Bangla Dubbed",
        "category/dual-audio-movies" to "Multi Audio Movies",
        "category/tv-series" to "TV Series",
        "category/bollywood-movies" to "Bollywood Movies",
        "category/bangla-movies" to "Bengali Movies",
        "category/hollywood-movies" to "Hollywood Movies",
    )

    // ------------------------------------------------------------------ fetch

    private suspend fun fetchDoc(url: String): Document? {
        return try {
            val res = app.get(url, headers = headers, timeout = 60)
            if (res.isSuccessful) res.document else null
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ cards

    private fun parseCards(doc: Document): List<SearchResponse> {
        return doc.select("div.single-post").mapNotNull { post ->
            val title = post.select(".post-title").text().replace(Regex("\\s+"), " ").trim()
                .ifBlank { post.select("h2").text().trim() }
            if (title.isBlank()) return@mapNotNull null
            val a = post.selectFirst(".thumb > a") ?: return@mapNotNull null
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.isBlank() || !href.startsWith("http")) return@mapNotNull null
            val img = post.selectFirst(".thumb img")
            val poster: String? = if (img == null) null else img.attr("src")
                .ifBlank { img.attr("data-src") }
                .ifBlank { img.attr("srcset").substringBefore(" ").trim() }
                .ifBlank { null }
            if (isSeries(href, title)) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }.distinctBy { it.url }
    }

    private fun isSeries(url: String, title: String): Boolean {
        return url.contains("/tv-series/") ||
            Regex("(?i)\\bs\\d{1,2}(?:e\\d{1,3})?\\b|season\\s*\\d|web.?series|\\bepisode\\b")
                .containsMatchIn(title)
    }

    // ------------------------------------------------------------- main page

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val path = request.data.trim('/')
        val url = when {
            page <= 1 -> if (path.isEmpty()) "$mainUrl/" else "$mainUrl/$path/"
            else -> if (path.isEmpty()) "$mainUrl/page/$page/" else "$mainUrl/$path/page/$page/"
        }
        val doc = fetchDoc(url)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = parseCards(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val doc = fetchDoc("$mainUrl/?s=" + URLEncoder.encode(q, "UTF-8"))
            ?: return emptyList()
        return parseCards(doc)
    }

    // ------------------------------------------------------------------ load

    /** CNCVerse data format: "quality|url ; quality|url". */
    private data class QLink(val quality: String, val url: String) {
        fun pack(): String = "$quality|$url"
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDoc(url) ?: throw IllegalArgumentException("post unreachable: $url")

        val title = doc.select(".name").text().replace(Regex("\\s+"), " ").trim()
            .ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: "" }
            .ifBlank { doc.selectFirst("h1")?.text()?.trim() ?: "" }
            .ifBlank { url }
        val year = Regex("(?<=\\()\\d{4}(?=\\))").find(title)?.value?.toIntOrNull()
        val image = doc.select("img.aligncenter").attr("src")
            .ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content") ?: "" }

        doc.select("br").append("\\n")
        val plot = listOf(
            doc.select(".single-post-title").text(),
            doc.select(".misc").text(),
            doc.select(".details").text().replace("\\n ", "\n"),
            doc.select(".storyline").text(),
            doc.select(".production").text().replace("\\n ", "\n"),
            doc.select(".media").text().replace("\\n ", "\n"),
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .joinToString("\n")
            .ifBlank { doc.selectFirst("meta[name=description]")?.attr("content")?.trim() ?: "" }
        val tags = doc.select("a[rel=tag]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct().take(6).ifEmpty { null }

        // Download sections in document order. 1 (or 0 with stray links) =
        // movie; several per-episode sections = series.
        val sections = LinkedHashMap<String, MutableList<QLink>>()
        for (div in doc.select("div.post-section-title.download, div.post-section-title")) {
            val secTitle = div.text().replace(Regex("\\s+"), " ").trim().ifBlank { "Download Now" }
            val bucket = sections.getOrPut(secTitle) { mutableListOf() }
            var sib = div.nextElementSibling()
            while (sib != null && sib.tagName() == "p") {
                for (a in sib.select("a")) {
                    val cls = a.classNames()
                    if (!cls.any { it == "sd" || it == "hd" || it == "hevc" }) continue
                    val href = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
                    if (href.isEmpty() || !href.startsWith("http")) continue
                    val q = qualityLabel(a.text(), cls)
                    if (bucket.none { it.url == href }) bucket.add(QLink(q, href))
                }
                sib = sib.nextElementSibling()
            }
        }

        // Resilience: page-wide sd/hd/hevc anchors when no section wrapper exists.
        if (sections.values.all { it.isEmpty() }) {
            val bucket = sections.getOrPut("Download Now") { mutableListOf() }
            for (a in doc.select("a.sd, a.hd, a.hevc")) {
                val href = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
                if (href.startsWith("http") && bucket.none { it.url == href }) {
                    bucket.add(QLink(qualityLabel(a.text(), a.classNames()), href))
                }
            }
        }

        val epRegex = Regex("Epi-?\\s*(\\d{1,3})(?:-(\\d{1,3}))?", RegexOption.IGNORE_CASE)
        val epSectionCount = sections.keys.count { epRegex.containsMatchIn(it) }
        val isSeriesPost = isSeries(url, title) || epSectionCount >= 2

        return if (isSeriesPost) {
            val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
            for ((secTitle, links) in sections) {
                if (links.isEmpty()) continue
                val m = epRegex.find(secTitle)
                val start = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val end = m?.groupValues?.get(2)?.toIntOrNull() ?: start
                val actualStart = if (start <= 0) 1 else start
                val actualEnd = if (end <= 0) actualStart else end
                val data = links.joinToString(" ; ") { it.pack() }
                val qualities = links.mapNotNull {
                    Regex("(\\d{3,4})p").find(it.quality)?.value
                }.distinct().joinToString("/")
                for (epNum in actualStart..actualEnd) {
                    episodes.add(newEpisode(data) {
                        name = "Episode $epNum" + if (qualities.isNotBlank()) " · $qualities" else ""
                        this.season = 1
                        this.episode = epNum
                    })
                }
            }
            episodes.sortBy { it.episode }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = image.ifBlank { null }
                this.plot = plot.ifBlank { null }
                this.tags = tags
                this.year = year
            }
        } else {
            val all = sections.values.flatten().distinctBy { it.url }
            val data = all.joinToString(" ; ") { it.pack() }
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = image.ifBlank { null }
                this.plot = plot.ifBlank { null }
                this.tags = tags
                this.year = year
            }
        }
    }

    /** "Download in 720p quality" / class sd|hd|hevc -> "720p" style label. */
    private fun qualityLabel(text: String, classes: Collection<String>): String {
        val t = text.lowercase()
        return when {
            "2160" in t || "4k" in t -> "4K"
            "1080" in t -> "1080p"
            "720" in t -> "720p"
            "480" in t -> "480p"
            "360" in t -> "360p"
            classes.contains("hevc") -> "HEVC"
            classes.contains("hd") -> "HD"
            classes.contains("sd") -> "SD"
            else -> "Unknown"
        }
    }

    // ------------------------------------------------------------- loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val counted = mutableSetOf<String>()
        var found = false

        for (item in data.split(" ; ")) {
            val parts = item.split("|", limit = 2)
            val link = parts.getOrNull(1)?.trim() ?: item.trim()
            if (link.isEmpty() || !link.startsWith("http")) continue
            try {
                if (link.contains("savelinks")) {
                    // Savelinks view page: real file hosts sit in <ul><li><a href=http>.
                    val res = app.get(link, headers = headers, referer = "$mainUrl/", timeout = 45)
                    if (!res.isSuccessful) continue
                    val pageUrl = res.url
                    for (a in res.document.select("ul li a[href^=http], ul li a[href^=https]")) {
                        val host = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
                        if (!isHostLink(host)) continue
                        if (dispatchHost(host, pageUrl, subtitleCallback, callback, counted)) found = true
                    }
                } else {
                    if (dispatchHost(link, "$mainUrl/", subtitleCallback, callback, counted)) found = true
                }
            } catch (e: Exception) {
                // try the next item
            }
        }
        return found
    }

    /** Social/site-chrome filter for savelinks pages. */
    private fun isHostLink(link: String): Boolean {
        val l = link.lowercase()
        if (!l.startsWith("http")) return false
        return listOf("savelinks.me", "mlsbd.co", "t.me", "telegram", "whatsapp",
            "facebook", "twitter", "javascript:", "login").none { it in l }
    }

    /**
     * Host-based dispatch into the dedicated ExtractorApis, with a
     * SourceStats wrapper that counts one view per host per loadLinks call
     * and appends speed/views to every label.
     */
    private suspend fun dispatchHost(
        link: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        counted: MutableSet<String>,
    ): Boolean {
        val hostKey = SourceStats.hostKeyOf(link)
        var emitted = false
        val wrapped: (ExtractorLink) -> Unit = { linkOut ->
            emitted = true
            if (counted.add(hostKey)) SourceStats.record(hostKey)
            callback(linkOut)
        }
        val l = link.lowercase()
        when {
            "pixeldrain.com" in l -> resolvePixeldrain(link, wrapped)
            "filepress" in l || "filebee" in l -> FilePress.getUrl(link, referer, subtitleCallback, wrapped)
            "multicloud" in l || "multidownload" in l -> MultiCloudLinks.getUrl(link, referer, subtitleCallback, wrapped)
            "gdxfiles" in l -> GDxFiles.getUrl(link, referer, subtitleCallback, wrapped)
            "gdflix" in l -> GDFlix.getUrl(link, referer, subtitleCallback, wrapped)
            "hubcloud" in l || "hubdrive" in l -> HubCloud.getUrl(link, referer, subtitleCallback, wrapped)
            "shikshakdaak" in l -> Shikshakdaak.getUrl(link, referer, subtitleCallback, wrapped)
            "gofile" in l -> Gofile.getUrl(link, referer, subtitleCallback, wrapped)
            "filesdl" in l -> Filesdl.getUrl(link, referer, subtitleCallback, wrapped)
            "m4ulinks" in l -> M4ulinks.getUrl(link, referer, subtitleCallback, wrapped)
            "savelinks" in l -> {
                // Nested savelinks page — resolve its host links recursively.
                try {
                    val res = app.get(link, headers = headers, referer = referer, timeout = 45)
                    if (res.isSuccessful) {
                        for (a in res.document.select("ul li a[href^=http], a[href^=http]")) {
                            val nested = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
                            if (!isHostLink(nested) || nested.contains("savelinks")) continue
                            if (dispatchHost(nested, res.url, subtitleCallback, callback, counted)) emitted = true
                        }
                    }
                } catch (e: Exception) { /* skip */ }
            }
            else -> {
                // Unknown host: let CloudStream's global registry try, but
                // only accept it when a matching extractor actually exists.
                try {
                    loadExtractor(link, referer, subtitleCallback, wrapped)
                } catch (e: Exception) { /* skip */ }
            }
        }
        return emitted
    }

    private suspend fun resolvePixeldrain(
        link: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = link.substringAfter("pixeldrain.com/").trimStart('/')
            .substringBefore("?").substringBefore("/").trim()
        if (id.isBlank() || id.startsWith("api")) return
        val url = "https://pixeldrain.com/api/file/$id?download"
        callback(
            newExtractorLink(
                name,
                SourceStats.decorate("pixeldrain", "${qLabel(mlsbdQualityNum(link))}Pixeldrain"),
                url
            ) {
                this.quality = mlsbdQualityNum(link)
                this.referer = "https://pixeldrain.com/"
            }
        )
    }
}
