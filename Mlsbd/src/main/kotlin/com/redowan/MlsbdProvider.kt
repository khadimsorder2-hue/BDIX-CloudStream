package com.redowan

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder

/**
 * MLSBD (mlsbd.co — "The Largest Movie Link Store of Bangladesh").
 *
 * Site anatomy (probed live):
 *  - Catalog & search pages render <div class="single-post"> cards with a
 *    poster (mlsbd-image.com / imgnest / tmdb), an <h2 class="post-title">
 *    and a link to the post. Pagination is WordPress style /page/N/.
 *  - Every post lists download buttons that point at https://savelinks.me
 *    view pages labelled "Download in 480p/720p/1080p quality" and
 *    "Watch Online in High Quality".
 *  - A savelinks view page hands out the real file hosts:
 *      gdxfiles.com / gdflix.dev  (GDFlix clones -> pages carry direct
 *          pub-*.r2.dev?token= links that serve video/mkv bytes),
 *      new3.multicloudlinks.com   ( -> player.php ArtPlayer source on
 *          dr1.multidownload.lol + more mirrors),
 *      filepress (Cloudflare-gated, skipped).
 */
class MlsbdProvider : MainAPI() {
    override var mainUrl = "https://mlsbd.co"
    override var name = "MLSBD"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)

    private val savelinksUrl = "https://savelinks.me"

    private val docHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept-Language" to "bn,en-US;q=0.9,en;q=0.8",
    )

    override val mainPage = mainPageOf(
        "/category/bangla-movies/" to "Bengali Movies",
        "/category/bollywood-movies/" to "Bollywood Movies",
        "/category/hollywood-movies/" to "Hollywood Movies",
        "/category/hindi-dubbed-movies/" to "Hindi Dubbed",
        "/category/bangla-dubbed/" to "Bangla Dubbed",
        "/category/dual-audio-movies/" to "Dual Audio",
        "/category/web-series/" to "Web Series",
        "/category/tv-series/" to "TV Series",
        "/category/hoichoi/" to "Hoichoi Originals",
        "/category/chorki-originals/" to "Chorki Originals",
        "/category/addatimes/" to "Addatimes Originals",
        "/category/binge-originals/" to "Binge Originals",
        "/category/bioscope-original/" to "Bioscope Originals",
        "/category/anime/" to "Anime",
        "/category/cartoon-series/" to "Cartoon Series",
        "/category/4k/" to "4K UHD",
        "/category/1080p/" to "1080p",
    )

    /** One savelinks button captured on the post page. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SavelinkEntry(
        val code: String? = null,
        val label: String? = null,
        val quality: String? = null,
    )

    private fun qualityLabel(text: String): String? {
        val m = Regex("(?i)\\b(2160|1440|1080|720|576|480|360|240)p?\\b|\\b4k\\b").find(text)
            ?: return null
        val token = m.value.lowercase()
        return when {
            token == "4k" -> "2160p"
            token.endsWith("p") -> token
            else -> "${token}p"
        }
    }

    /** Parses one catalog/search/post-list page into cards. */
    private fun parseCards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        return doc.select("div.single-post").mapNotNull { card ->
            val a = card.selectFirst("a[href*=$mainUrl]") ?: return@mapNotNull null
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.isBlank() || href.contains("/category/")) return@mapNotNull null
            val title = card.selectFirst("h2.post-title, .post-title")?.text()?.trim()
                ?: a.attr("title").ifBlank { a.text() }
            if (title.isBlank()) return@mapNotNull null
            val img = card.selectFirst("img")?.attr("src")
                ?: card.selectFirst("source[srcset]")?.attr("srcset")
            val isSeries = Regex("(?i)\\bs\\d{1,2}\\b|season\\s*\\d|web.?series|episode")
                .containsMatchIn(title)
            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = img }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = img }
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) "$base/" else "$base/page/$page/"
        val doc = app.get(url, headers = docHeaders, timeout = 60, allowRedirects = true).document
        val items = parseCards(doc).distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val doc = try {
            app.get("$mainUrl/?s=" + URLEncoder.encode(q, "UTF-8"),
                headers = docHeaders, timeout = 60, allowRedirects = true).document
        } catch (e: Exception) {
            return emptyList()
        }
        return parseCards(doc).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = docHeaders, timeout = 60, allowRedirects = true).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: url
        val poster = doc.selectFirst(".post-content picture img, article picture img, picture img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val tags = doc.select("a[rel=tag]").map { it.text() }.takeIf { it.isNotEmpty() }

        // Every savelinks button on the post: code + human label + parsed quality.
        val entries = mutableListOf<SavelinkEntry>()
        val seen = mutableSetOf<String>()
        for (a in doc.select("a[href*=savelinks.me]")) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            val code = Regex("savelinks\\.me/view/([A-Za-z0-9]+)").find(href)?.groupValues?.get(1)
                ?: continue
            if (!seen.add(code)) continue
            val label = a.text().replace(Regex("\\s+"), " ").trim()
            entries.add(SavelinkEntry(code, label, qualityLabel(label)))
        }

        val isSeries = Regex("(?i)\\bs\\d{1,2}\\b|season\\s*\\d|web.?series").containsMatchIn(title)

        return if (isSeries && entries.isNotEmpty()) {
            // Savelink buttons are quality variants of the season, not
            // numbered episodes — surface them as honestly labelled items.
            val episodes = entries.mapIndexed { idx, entry ->
                newEpisode(entry.toJson()) {
                    name = entry.quality ?: ("Link ${idx + 1}")
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = doc.selectFirst(".post-content p")?.text()?.trim()
                this.tags = tags
            }
        } else {
            val data = entries.joinToString("|") { it.toJson() }
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.plot = doc.selectFirst(".post-content p")?.text()?.trim()
                this.tags = tags
            }
        }
    }

    private fun SavelinkEntry.toJson(): String =
        com.lagradost.cloudstream3.mapper.writeValueAsString(this)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val entries = data.split("|")
            .mapNotNull { tryParseJson<SavelinkEntry>(it) }
            .filter { !it.code.isNullOrBlank() }
        if (entries.isEmpty()) return false

        var found = false
        for (entry in entries) {
            try {
                val hosts = resolveSavelink(entry.code!!)
                for (host in hosts) {
                    callback(newExtractorLink(
                        name, "$name [${host.label}]", host.url, host.type
                    ) {
                        this.quality = when (host.quality) {
                            "2160p" -> 2160
                            "1440p" -> 1440
                            "1080p" -> 1080
                            "720p" -> 720
                            "480p" -> 480
                            "360p" -> 360
                            else -> 0
                        }
                        this.referer = host.referer
                        this.headers = docHeaders
                    })
                    found = true
                }
            } catch (e: Exception) {
                // try the next button
            }
        }
        return found
    }

    data class ResolvedHost(
        val url: String,
        val label: String,
        val quality: String?,
        val type: ExtractorLinkType,
        val referer: String,
    )

    /** Follows one savelinks.me/view/<code> page and pulls direct links. */
    private suspend fun resolveSavelink(code: String): List<ResolvedHost> {
        val viewUrl = "$savelinksUrl/view/$code"
        val doc = try {
            app.get(viewUrl, headers = docHeaders + mapOf("Referer" to "$mainUrl/"),
                timeout = 45, allowRedirects = true).document
        } catch (e: Exception) {
            return emptyList()
        }

        val hosts = doc.select("a[href]")
            .map { it.attr("abs:href") }
            .filter { it.isNotBlank() }
            .distinctBy { it.substringBefore("?") }
        val out = mutableListOf<ResolvedHost>()

        for (host in hosts) {
            when {
                // GDFlix clones carry direct pub-*.r2.dev?token= links in-page.
                host.contains("gdflix.") || host.contains("gdxfiles.") -> {
                    out.addAll(resolveGdflix(host))
                }
                // MultiCloud view -> ArtPlayer source on dr1.multidownload.lol.
                host.contains("multicloudlinks.") -> {
                    out.addAll(resolveMulticloud(host))
                }
                // filepress is Cloudflare-gated from servers; skip quietly.
                host.contains("filepress.") || host.contains("filebee.") -> { /* skip */ }
            }
        }
        // Prefer direct r2.dev downloads first.
        return out.sortedByDescending { it.url.contains("r2.dev") }
    }

    private suspend fun resolveGdflix(url: String): List<ResolvedHost> {
        val res = try {
            app.get(url, headers = docHeaders, timeout = 45, allowRedirects = true)
        } catch (e: Exception) {
            return emptyList()
        }
        if (!res.isSuccessful) return emptyList()
        val html = res.text
        val doc = res.document

        // The <title> holds the real filename: "GDFlix | <name>.mkv".
        val filename = doc.title().substringAfter("|").trim().ifBlank { "download" }
        val quality = qualityLabel(filename)

        val links = mutableListOf<ResolvedHost>()
        // 1) Direct Cloudflare R2 object storage (proven video/mkv bytes).
        Regex("https://pub-[A-Za-z0-9-]+\\.r2\\.dev/[^\"'<>\\s]+").findAll(html)
            .map { it.value.replace("&amp;", "&") }
            .distinct()
            .take(2)
            .forEach { links.add(ResolvedHost(it, "$quality GDFlix R2".trim(), quality, ExtractorLinkType.VIDEO, url)) }
        // 2) instant CDN / indexserver mirrors as fallbacks.
        Regex("https://instant\\.busycdn\\.xyz/[A-Za-z0-9]+").findAll(html)
            .map { it.value }.distinct().take(1)
            .forEach { links.add(ResolvedHost(it, "$quality BusyCDN".trim(), quality, ExtractorLinkType.VIDEO, url)) }
        if (links.isEmpty()) {
            // Nothing direct? Hand the file page over as a browsable fallback.
            val title = filename.substringBeforeLast('.').trim()
            links.add(ResolvedHost(url, "GDFlix · $title".take(60), quality, ExtractorLinkType.VIDEO, url))
        }
        return links
    }

    private suspend fun resolveMulticloud(url: String): List<ResolvedHost> {
        val res = try {
            app.get(url, headers = docHeaders, timeout = 45, allowRedirects = true)
        } catch (e: Exception) {
            return emptyList()
        }
        if (!res.isSuccessful) return emptyList()
        val html = res.text
        val out = mutableListOf<ResolvedHost>()

        // player.php?v=<code> embeds an ArtPlayer whose streamSrc is a direct
        // multidownload.lol watch URL (Google-Drive-backed streaming proxy).
        val player = Regex("https://[^\"'<>\\s]*multicloudlinks\\.com/player\\.php/\\?v=[A-Za-z0-9]+")
            .find(html)?.value
        if (player != null) {
            try {
                val pres = app.get(player.replace("/player.php/", "/player.php"),
                    headers = docHeaders + mapOf("Referer" to url),
                    timeout = 45, allowRedirects = true)
                val m = Regex("(?:const|let|var)\\s+streamSrc\\s*=\\s*[\"']([^\"']+)[\"']")
                    .find(pres.text)
                val src = m?.groupValues?.get(1)
                    ?: Regex("[\"'](https?://[^\"']*multidownload\\.lol[^\"']*)[\"']")
                        .find(pres.text)?.groupValues?.get(1)
                if (src != null) {
                    out.add(ResolvedHost(
                        src.replace("&amp;", "&"), "MultiCloud Watch", null,
                        ExtractorLinkType.VIDEO, player))
                }
            } catch (e: Exception) {
                // fall through to download tokens
            }
        }
        // Tokenised download link on the view page itself (may 404 per-file).
        Regex("https://dr\\d*\\.multidownload\\.lol/d/[^\"'<>\\s]+").findAll(html)
            .map { it.value.replace("&amp;", "&") }
            .distinct().take(1)
            .forEach {
                out.add(ResolvedHost(it, "MultiCloud Direct", null, ExtractorLinkType.VIDEO, url))
            }
        return out
    }
}
