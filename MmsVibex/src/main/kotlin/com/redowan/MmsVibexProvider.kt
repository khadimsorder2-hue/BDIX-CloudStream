package com.redowan

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

/**
 * MmsVibeX (https://mmsvibex.fit) - NSFW provider
 *
 * Features:
 *  - Home page with ALL sorts (latest / popular / most-viewed / longest / random)
 *  - ALL categories (Bangladeshi, Indian, Desi, Pakistani, Sri Lankan, Nepali, Arab, Cam, Hidden Cam, Gay/Shemale, Pashto, Pathan, Faphouse, Others)
 *  - Albums (actor/playlists) as series with each video as an episode
 *  - Paginated search via the WordPress REST API (clean JSON, no ads)
 *  - Ad-free playback: direct mp4 extracted from the site player config (mvp-config),
 *    VAST/magsrv ad calls are never touched
 *  - Multi-source: every quality/variant on the player page is returned
 */
open class MmsVibexProvider : MainAPI() {
    override var mainUrl = "https://mmsvibex.fit"
    override var name = "MmsVibeX"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val instantLinkLoading = true
    override var lang = "bn"
    override val supportedTypes = setOf(
        TvType.NSFW,
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** per_page for the REST API listing */
        private const val PER_PAGE = 24
    }

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    // ---------------- ALL sorts + categories + albums ----------------
    override val mainPage = mainPageOf(
        // --- sorting ---
        "/?filter=latest" to "Latest",
        "/?filter=popular" to "Popular",
        "/?filter=most-viewed" to "Most Viewed",
        "/?filter=longest" to "Longest",
        "/?filter=random" to "Random",
        // --- albums (actor / playlists) ---
        "/actors/" to "Albums",
        // --- categories ---
        "/category/bangladesi/" to "Bangladeshi",
        "/category/indian/" to "Indian",
        "/category/desi/" to "Desi",
        "/category/pakistani/" to "Pakistani",
        "/category/sri-lankan-sl/" to "Sri Lankan",
        "/category/nepali/" to "Nepali",
        "/category/arab/" to "Arab",
        "/category/cam/" to "Cam",
        "/category/hidden-cam/" to "Hidden Cam",
        "/category/gay-shemale/" to "Gay/Shemale",
        "/category/pashto/" to "Pashto",
        "/category/pathan/" to "Pathan",
        "/category/faphouse/" to "Faphouse",
        "/category/others/" to "Others"
    )

    // ---------------- URL builders ----------------
    private fun pageUrl(data: String, page: Int): String {
        return when {
            data.startsWith("/actors") ->
                if (page <= 1) "$mainUrl/actors/" else "$mainUrl/actors/page/$page/"
            data.startsWith("/category/") ->
                if (page <= 1) "$mainUrl$data" else "$mainUrl${data}page/$page/"
            data.startsWith("/?filter=") ->
                if (page <= 1) "$mainUrl$data" else "$mainUrl/page/$page/?${data.substringAfter("?")}"
            else ->
                if (page <= 1) "$mainUrl$data" else "$mainUrl/page/$page/"
        }
    }

    // ---------------- HOME ----------------
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = pageUrl(request.data, page)
        val doc = app.get(url, headers = headers).document

        val isAlbums = request.data.startsWith("/actors")
        val items: List<SearchResponse>

        if (isAlbums) {
            items = doc.select("article.thumb-block").mapNotNull { toAlbumResult(it) }
        } else {
            items = doc.select("article.video-preview-item")
                .filterNot { isAdCard(it) }
                .mapNotNull { toVideoResult(it) }
        }

        return newHomePageResponse(
            request.name,
            items,
            items.isNotEmpty()
        )
    }

    private fun isAdCard(post: Element): Boolean =
        post.classNames().any { it.startsWith("wpst-provider") }

    private fun cleanImg(src: String?): String? {
        if (src.isNullOrBlank()) return null
        if (src.contains("px.gif") || src.startsWith("data:")) return null
        return src
    }

    // ---------------- card mappers ----------------
    private fun toVideoResult(post: Element): SearchResponse? {
        val a = post.selectFirst("a[href]") ?: return null
        val url = a.attr("href")
        if (url.isBlank()) return null

        val title = post.selectFirst(".title")?.text()?.takeIf { it.isNotBlank() }
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: post.selectFirst(".entry-header")?.text()?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = cleanImg(post.selectFirst(".post-thumbnail-container img")?.attr("src"))
            ?: cleanImg(post.selectFirst(".post-thumbnail img")?.attr("data-src"))
            ?: cleanImg(post.selectFirst("img")?.attr("data-src"))
            ?: cleanImg(post.attr("data-main-thumb"))

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun toAlbumResult(post: Element): SearchResponse? {
        val a = post.selectFirst("a[href]") ?: return null
        var url = a.attr("href")
        if (url.isBlank()) return null

        // theme links look like https://mmsvibex.fit?actors=slug -> normalize to /actor/slug/
        if (url.contains("?actors=")) {
            val slug = url.substringAfter("?actors=").trimEnd('/').trimEnd('&')
            if (slug.isBlank()) return null
            url = "$mainUrl/actor/$slug/"
        } else if (!url.startsWith("http")) {
            url = mainUrl + (if (url.startsWith("/")) url else "/$url")
        }

        val title = post.selectFirst(".actor-title")?.text()?.takeIf { it.isNotBlank() }
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: return null

        val poster = cleanImg(post.selectFirst("img")?.attr("data-src"))
            ?: cleanImg(post.selectFirst("img")?.attr("src"))
            ?: cleanImg(post.attr("data-main-thumb"))

        return newMovieSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // ---------------- SEARCH (WordPress REST API, paginated) ----------------
    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)

        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        val apiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$enc&per_page=$PER_PAGE&page=$page&_embed"

        val res = app.get(apiUrl, headers = headers)
        val items = AppUtils.parseJson<List<WpPost>>(res.text).orEmpty()
            .filter { it.link?.contains("/photos/") != true }
            .mapNotNull { toSearchResult(it) }

        val hasNext = res.headers["x-wp-totalpages"]?.toIntOrNull()?.let { page < it } ?: (items.size >= PER_PAGE)
        return newSearchResponseList(items, hasNext)
    }

    private fun toSearchResult(post: WpPost): SearchResponse? {
        val link = post.link ?: return null
        val title = post.title?.rendered
            ?.replace("&amp;", "&")
            ?.replace("&#038;", "&")
            ?.replace("&quot;", "\"")
            ?.replace("&#8217;", "'")
            ?.trim()
            ?: return null
        val thumb = post.embedded?.featuredMedia?.firstOrNull()?.sourceUrl
        return newMovieSearchResponse(title, link, TvType.NSFW) {
            this.posterUrl = thumb
        }
    }

    // ---------------- LOAD ----------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        // ---------- ALBUM (actor / playlist) ----------
        if (url.contains("/actor/")) {
            val title = doc.selectFirst("h1.page-title, h1")?.text()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst(".actor-title")?.text()
                ?: "Album"

            // use first video card thumb as album art (never the site logo)
            val poster = cleanImg(doc.selectFirst("article .post-thumbnail-container img")?.attr("src"))
                ?: cleanImg(doc.selectFirst("article img")?.attr("data-src"))
                ?: cleanImg(doc.selectFirst("article img")?.attr("src"))
            val plot = doc.selectFirst(".page-description, .entry-content, .term-description")?.text()?.trim()

            val episodes = mutableListOf<Episode>()
            fun collectVideos(d: org.jsoup.nodes.Document) {
                d.select("article.video-preview-item")
                    .filterNot { isAdCard(it) }
                    .forEach { el ->
                        val a = el.selectFirst("a[href]") ?: return@forEach
                        val link = a.attr("href")
                        if (link.isBlank()) return@forEach
                        val name = el.selectFirst(".title")?.text()?.takeIf { it.isNotBlank() }
                            ?: a.attr("title").takeIf { it.isNotBlank() }
                            ?: "Video ${episodes.size + 1}"
                        episodes.add(
                            newEpisode(link) {
                                this.name = name
                                this.episode = episodes.size + 1
                            }
                        )
                    }
            }
            collectVideos(doc)

            // follow album pagination if the theme exposes it (max 3 extra pages)
            val nextPages = doc.select("a[href*=\"/page/\"]")
                .map { it.attr("href") }
                .filter { it.contains("/actor/") }
                .mapNotNull { Regex("/page/(\\d+)/").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                .distinct()
                .sorted()
                .take(3)
            for (p in nextPages) {
                try {
                    val nd = app.get("$mainUrl/actor/${url.trimEnd('/').substringAfterLast("/actor/")}/page/$p/", headers = headers).document
                    val before = episodes.size
                    collectVideos(nd)
                    if (episodes.size == before) break
                } catch (e: Exception) {
                    break
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = listOf("Album")
            }
        }

        // ---------- SINGLE VIDEO ----------
        val playerCfg = parseMvpConfig(doc)
        val title = doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?: "MmsVibeX Video"

        val poster = playerCfg?.poster
            ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val plot = doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")
        val duration = playerCfg?.durationHint?.toInt()

        val tags = doc.select("a[href*=\"/tag/\"]").map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)

        // direct sources (ad-free): every source on the player page
        val sources = extractSources(doc, url)
        val data = if (sources.isEmpty()) url else sources.joinToString("||") { it.first }

        return newMovieLoadResponse(title, url, TvType.NSFW, data) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags.ifEmpty { listOf("MmsVibeX") }
            this.duration = duration
        }
    }

    // ---------------- player config ----------------
    private data class MvpConfig(
        val poster: String? = null,
        val durationHint: Long? = null,
        val sources: List<MvpSource>? = null
    )

    private data class MvpSource(
        val url: String? = null,
        val mimeType: String? = null,
        val quality: Int? = null,
        val label: String? = null
    )

    private fun parseMvpConfig(doc: org.jsoup.nodes.Document): MvpConfig? {
        val script = doc.selectFirst("script.mvp-config")?.data()
            ?: doc.html().let { html ->
                Regex("class=\"mvp-config\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                    .find(html)?.groupValues?.get(1)
            }
            ?: return null
        return try {
            AppUtils.parseJson<MvpConfig>(script)
        } catch (e: Exception) {
            null
        }
    }

    private fun isDirectMedia(u: String): Boolean {
        val l = u.lowercase().substringBefore("?")
        return l.endsWith(".mp4") || l.endsWith(".m3u8") || l.endsWith(".mkv") ||
            l.endsWith(".webm") || l.endsWith(".m4v") || l.endsWith(".mov") ||
            l.contains("/processed/") || l.contains("/trailers/")
    }

    /** All playable sources found on a video page, with labels */
    private fun extractSources(doc: org.jsoup.nodes.Document, pageUrl: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()

        // 1) mvp-config JSON sources (official player config)
        parseMvpConfig(doc)?.sources?.forEach { s ->
            val u = s.url ?: return@forEach
            if (u.isBlank()) return@forEach
            val label = when {
                !s.label.isNullOrBlank() && (s.quality ?: 0) > 0 -> "${s.label} - ${s.quality}p"
                !s.label.isNullOrBlank() -> s.label
                (s.quality ?: 0) > 0 -> "${s.quality}p"
                else -> "Original"
            }
            out.add(u to label)
        }

        // 2) <video><source> tags
        if (out.isEmpty()) {
            doc.select("video source[src]").forEach {
                val u = it.attr("src")
                if (u.isNotBlank()) out.add(u to (it.attr("title").ifBlank { "Original" }))
            }
        }

        // 3) raw scan for cdn mp4 (last resort)
        if (out.isEmpty()) {
            Regex("https://cdn\\.mmsvibex\\.fit/[^\"'\\\\\\s]+\\.mp4[^\"'\\\\\\s]*")
                .findAll(doc.html())
                .forEach { out.add(it.value to "Original") }
        }

        return out.distinctBy { it.first }
    }

    // ---------------- LINKS ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        var found = false
        val candidates = if (data.contains("||")) data.split("||") else listOf(data)

        for (c in candidates.map { it.trim() }.filter { it.isNotBlank() }) {
            val pairs: List<Pair<String, String>> = if (isDirectMedia(c)) {
                listOf(c to "Original")
            } else {
                try {
                    val doc = app.get(c, headers = headers).document
                    extractSources(doc, c)
                } catch (e: Exception) {
                    emptyList()
                }
            }

            for ((linkUrl, label) in pairs) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$name - $label",
                        url = linkUrl
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = "$mainUrl/"
                        this.headers = mapOf(
                            "User-Agent" to UA,
                            "Referer" to "$mainUrl/"
                        )
                    }
                )
                found = true
            }
        }
        return found
    }

    // ---------------- REST API DTOs ----------------
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class WpPost(
        val id: Long? = null,
        val date: String? = null,
        val slug: String? = null,
        val link: String? = null,
        val title: WpTitle? = null,
        val embedded: WpEmbedded? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class WpTitle(val rendered: String? = null)

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class WpEmbedded(
        @com.fasterxml.jackson.annotation.JsonProperty("wp:featuredmedia")
        val featuredMedia: List<WpMedia>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class WpMedia(val sourceUrl: String? = null)
}
