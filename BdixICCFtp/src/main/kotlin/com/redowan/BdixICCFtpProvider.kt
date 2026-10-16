package com.redowan

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * (BDIX) ICC Ftp - http://10.16.100.244
 *
 * Rewritten with:
 *  - Session (vfw.php gate) support with auto-refresh + legacy index.php fallback
 *  - ALL ICC FTP categories (Movies / TV Shows / Others)
 *  - HTML search (command.php cSearch JSON) + dashboard psearch fallback
 *  - All possible link sources: every video file on the player page is returned
 *    with quality labels + custom headers so nothing 403s inside the player
 */
open class BdixICCFtpProvider : MainAPI() {
    override var mainUrl = "http://10.16.100.244"
    override var name = "(BDIX) ICC Ftp"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val instantLinkLoading = true
    override var lang = "bn"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.AsianDrama,
        TvType.Documentary,
        TvType.OVA,
        TvType.Others
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val VIDEO_EXT =
            listOf(".mp4", ".mkv", ".avi", ".m4v", ".webm", ".mov", ".wmv", ".ts", ".flv", ".3gp")
    }

    // ---------------- ALL ICC FTP categories ----------------
    override val mainPage = mainPageOf(
        "0" to "Latest",
        // ----- Movies -----
        "59" to "Bangla Movies (BD)",
        "60" to "Bangla Movies (Kolkata)",
        "2" to "Hindi Movies",
        "19" to "English Movies",
        "32" to "South Indian (Hindi Dub)",
        "73" to "South Indian Movies",
        "43" to "Dual Audio Movies",
        "44" to "Exclusive Full-HD",
        "74" to "4K Movies",
        "9" to "3D Movies",
        "33" to "Animated Movies",
        "83" to "Anime Movies",
        "75" to "Korean Movies",
        "76" to "Chinese Movies",
        "77" to "Pakistani Movies",
        "71" to "Punjabi Movies",
        "79" to "Indonesian Movies",
        "80" to "Japanese Movies",
        "64" to "Other Foreign Movies",
        "41" to "Documentaries",
        // ----- TV Shows -----
        "36" to "English Series",
        "37" to "Hindi Series",
        "39" to "Bangla Serials",
        "34" to "Bangla Drama",
        "35" to "Bangla Telefilm",
        "78" to "Serials (Anime)",
        "82" to "Serials (Animation)",
        "72" to "Serials (Dual Audio)",
        "81" to "Serials (Documentaries)",
        "70" to "Serials (Others)",
        "52" to "WWE",
        "38" to "Awards",
        // ----- Others -----
        "66" to "Kids (Cartoon)",
        "53" to "Learning",
        "68" to "E-Books"
    )

    private var currentSession: String? = null

    private fun getHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    // ---------------- Session (vfw.php gate) ----------------
    private suspend fun getSession(): String {
        currentSession?.let { s -> if (s.isNotBlank()) return s }
        return try {
            val res = app.get(mainUrl, headers = getHeaders(), cacheTime = 0, timeout = 30)
            var s = res.url.substringAfter("session=", "").substringBefore("&")
            if (s.isBlank()) {
                s = Regex("session=([a-zA-Z0-9_-]{8,})").find(res.text)?.groupValues?.get(1) ?: ""
            }
            currentSession = s
            s
        } catch (_: Exception) {
            ""
        }
    }

    private fun refreshSession() {
        currentSession = null
    }

    private fun withSession(path: String, session: String): String {
        val base = if (path.startsWith("http")) path else "$mainUrl/$path"
        if (session.isBlank()) return base
        val stripped = base.substringBefore("&session=").substringBefore("?session=")
        return "$stripped${if (stripped.contains("?")) "&" else "?"}session=$session"
    }

    private fun playUrlFromHref(href: String): String? {
        if (!href.contains("play=")) return null
        val id = href.substringAfter("play=", "").substringBefore("&")
        if (id.isBlank()) return null
        return "$mainUrl/player.php?play=$id"
    }

    private fun Element.closestAnchor(): Element? {
        var cur: Element? = this
        while (cur != null) {
            if (cur.tagName() == "a") return cur
            cur = cur.parent()
        }
        return null
    }

    // ---------------- Main page ----------------
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val category = request.data
        var posts: List<SearchResponse> = emptyList()

        if (page <= 1) {
            posts = fetchDashboard(category)
            if (posts.isEmpty()) {
                refreshSession()
                posts = fetchDashboard(category)
            }
            if (posts.isEmpty()) {
                posts = fetchLegacyIndex(category)
            }
        } else {
            posts = fetchCommandPage(category, page)
        }

        return newHomePageResponse(request.name, posts, posts.isNotEmpty())
    }

    private suspend fun fetchDashboard(category: String): List<SearchResponse> {
        return try {
            val session = getSession()
            val doc = app.get(
                "$mainUrl/dashboard.php?session=$session&category=$category",
                headers = getHeaders(),
                cacheTime = 30,
                timeout = 30
            ).document
            parsePostElements(doc)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchLegacyIndex(category: String): List<SearchResponse> {
        return try {
            val doc = app.get(
                "$mainUrl/index.php?category=$category",
                headers = getHeaders(),
                cacheTime = 30,
                timeout = 30
            ).document
            parsePostElements(doc)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchCommandPage(category: String, page: Int): List<SearchResponse> {
        return try {
            val mediaType = "application/x-www-form-urlencoded".toMediaType()
            val body = "cpage=$page&category=$category".toRequestBody(mediaType)
            val doc = app.post(
                "$mainUrl/command.php",
                requestBody = body,
                headers = getHeaders()
            ).document
            parsePostElements(doc)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------------- Post parsing (grid + slider) ----------------
    private fun parsePostElements(doc: Document): List<SearchResponse> {
        val results = LinkedHashMap<String, SearchResponse>()

        fun addPost(href: String, title: String?, image: String?) {
            if (title.isNullOrBlank()) return
            val url = playUrlFromHref(href) ?: return
            if (results.containsKey(url)) return
            results[url] = newMovieSearchResponse(title.trim(), url, TvType.Movie) {
                this.posterUrl = fixImage(image)
            }
        }

        // 1) Grid posts: div.post / div.post-item containers
        doc.select("div.post, div.post-item").forEach { post ->
            val a = post.selectFirst("a[href*='play=']") ?: return@forEach
            val title = post.selectFirst(".title")?.text()?.ifBlank { null }
                ?: a.selectFirst("img")?.attr("alt")?.ifBlank { null }
                ?: a.attr("title")?.ifBlank { null }
            val img = post.selectFirst("img")?.attr("src")
                ?: a.selectFirst("img")?.attr("src")
            addPost(a.attr("href"), title, img)
        }

        // 2) post-wrapper anchors (legacy + new)
        doc.select("div.post-wrapper > a[href*='play=']").forEach { a ->
            val title = a.selectFirst(".title")?.text()?.ifBlank { null }
                ?: a.select("img").attr("alt")?.ifBlank { null }
            addPost(a.attr("href"), title, a.select("img").attr("src"))
        }

        // 3) Featured slider items (skip only if we already found grid posts)
        doc.select("#post-slider-multipost div.item, .slider.multipost div.item").forEach { item ->
            val a = item.closestAnchor()
            val href = a?.attr("href").orEmpty()
            if (!href.contains("play=")) return@forEach
            val title = item.selectFirst("div.title span")?.text()?.ifBlank { null }
                ?: item.selectFirst("div.title")?.text()?.ifBlank { null }
                ?: item.select("img").attr("alt")?.ifBlank { null }
            val style = item.selectFirst("div.img")?.attr("style").orEmpty()
            val styleImg = style.substringAfter("url('", "").substringBefore("')")
                .ifBlank { style.substringAfter("url(\"", "").substringBefore("\")") }
            val img = styleImg.ifBlank { item.select("img").attr("src") }
            addPost(href, title, img)
        }

        // 4) Bare anchors with play= (catch-all)
        if (results.isEmpty()) {
            doc.select("a[href*='play=']").forEach { a ->
                val title = a.selectFirst("img")?.attr("alt")?.ifBlank { null }
                    ?: a.select(".title").text()?.ifBlank { null }
                    ?: a.ownText()?.ifBlank { null }
                addPost(a.attr("href"), title, a.select("img").attr("src"))
            }
        }

        return results.values.toList()
    }

    // ---------------- Search ----------------
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        // Primary: ICC native search API (command.php cSearch -> JSON)
        try {
            val mediaType = "application/x-www-form-urlencoded".toMediaType()
            val body = "cSearch=${URLEncoder.encode(query, "UTF-8")}".toRequestBody(mediaType)
            val text = app.post(
                "$mainUrl/command.php",
                requestBody = body,
                headers = getHeaders()
            ).text
            if (text.isNotBlank() && text.trimStart().startsWith("[")) {
                val json = AppUtils.parseJson<SearchJson>(text)
                val mapped = json.mapNotNull { item ->
                    val name = item.name?.trim().takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
                    val id = item.id ?: return@mapNotNull null
                    val url = "$mainUrl/player.php?play=$id"
                    val type = if (item.type == "2") TvType.TvSeries else TvType.Movie
                    newMovieSearchResponse(name, url, type) {
                        this.posterUrl = item.image?.let { "$mainUrl/files/$it" }
                    }
                }
                if (mapped.isNotEmpty()) return mapped
            }
        } catch (_: Exception) {
        }

        // Fallback: dashboard psearch (needs token from dashboard HTML)
        return try {
            val session = getSession()
            val dash = app.get(
                "$mainUrl/dashboard.php?session=$session&category=0",
                headers = getHeaders(),
                cacheTime = 0,
                timeout = 30
            )
            val token = Regex("name=[\"']token[\"']\\s+value=[\"']([^\"']+)[\"']").find(dash.text)?.groupValues?.get(1) ?: ""
            val body = "token=${URLEncoder.encode(token, "UTF-8")}&psearch=${URLEncoder.encode(query, "UTF-8")}"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val doc = app.post(
                withSession("dashboard.php", session),
                requestBody = body,
                headers = getHeaders() + mapOf("Referer" to "$mainUrl/dashboard.php?session=$session")
            ).document
            parsePostElements(doc)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------------- Load ----------------
    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfter("play=", "").substringBefore("&")
        val session = getSession()
        val playerUrl = if (id.isNotBlank()) "$mainUrl/player.php?play=$id&session=$session" else withSession(url, session)
        val doc = app.get(playerUrl, headers = getHeaders(), timeout = 30).document

        // If the gate bounced us, refetch once with a fresh session
        val bounced = doc.location().orEmpty().contains("vfw.php") || doc.select(".modal-dialog").isEmpty() && doc.select(".table").isEmpty()
        val page = if (bounced && id.isNotBlank()) {
            refreshSession()
            app.get(
                "$mainUrl/player.php?play=$id&session=${getSession()}",
                headers = getHeaders(),
                timeout = 30
            ).document
        } else {
            doc
        }

        // ---- metadata (label based, works with both old & new table layouts) ----
        var modalTitle = page.selectFirst(".modal-title")?.text()?.trim().orEmpty()
        if (modalTitle.isBlank()) modalTitle = page.title().replace("ICC FTP SERVER", "", true).trim()

        var year: Int? = null
        var plot = ""
        var durationText = ""
        var categoryHint = ""
        var genreList: List<String> = emptyList()
        var firstRowText = ""

        page.select("table tr").forEachIndexed { index, row ->
            val cells = row.select("td")
            if (cells.isEmpty()) return@forEachIndexed
            if (index == 0 && cells.size < 2) {
                firstRowText = row.text().trim()
                return@forEachIndexed
            }
            if (cells.size >= 2) {
                val label = cells.first()!!.text().trim().trimEnd(':').lowercase()
                val value = cells.last()!!.text().trim()
                when {
                    label.contains("year") -> year = value.toIntOrNull() ?: year
                    label.contains("genre") || label.contains("generic") ->
                        genreList = value.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    label.contains("plot") || label.contains("discription") || label.contains("description") -> plot = value
                    label.contains("duration") || label.contains("run time") || label.contains("runtime") -> durationText = value
                    label.contains("category") -> categoryHint = value
                }
            }
        }

        val title = sequenceOf(
            modalTitle,
            firstRowText,
            page.select("table tr").first()?.text()?.trim().orEmpty()
        ).firstOrNull { it.isNotBlank() } ?: "ICC FTP"

        val poster = fixImage(
            page.selectFirst(".modal-dialog img")?.attr("src")
                ?: page.selectFirst(".col-md-4 img")?.attr("src")
                ?: page.selectFirst("table img")?.attr("src")
        )

        val trailer = page.selectFirst(".pull-left")?.attr("data-thevideo").orEmpty()
        val trailerData = mutableListOf<TrailerData>()
        if (trailer.isNotBlank()) {
            trailerData.add(
                TrailerData(
                    extractorUrl = trailer,
                    raw = false,
                    referer = mainUrl
                )
            )
        }

        val epItems = page.select(".btn-group > ul > li")
        val seriesHint = title.contains("season", true) ||
            title.contains("episode", true) ||
            categoryHint.contains("serial", true)

        if (epItems.isEmpty() && !seriesHint) {
            // ---------------- MOVIE ----------------
            val videoUrls = extractVideoLinks(page)
            val btnLink = page.selectFirst("a.btn")?.attr("href").orEmpty()
            val data = when {
                videoUrls.isNotEmpty() -> videoUrls.joinToString("||")
                btnLink.isNotBlank() -> absoluteUrl(btnLink)
                else -> playerUrl
            }
            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot.ifBlank { null }
                this.tags = genreList
                this.duration = getDurationFromString(durationText)
                this.trailers = trailerData
            }
        } else {
            // ---------------- SERIES ----------------
            val episodes = mutableListOf<Episode>()
            epItems.forEachIndexed { index, li ->
                val a = li.selectFirst("a") ?: return@forEachIndexed
                val href = a.attr("href")
                if (href.isBlank()) return@forEachIndexed
                val span = li.selectFirst("span")?.text().orEmpty()
                val rawName = (a.ownText().ifBlank { a.text() }).replace(span, "").trim()
                val epName = rawName.ifBlank { "Episode ${index + 1}" }
                val (season, episodeNum) = parseSeasonEpisode(epName, index + 1)
                episodes.add(
                    newEpisode(absoluteUrl(href)) {
                        this.name = epName
                        this.season = season
                        this.episode = episodeNum
                    }
                )
            }

            // Fallback: some serial pages list episodes as linked posts
            if (episodes.isEmpty()) {
                page.select("a[href*='play=']").forEachIndexed { index, a ->
                    val href = a.attr("href")
                    val epId = playUrlFromHref(href) ?: return@forEachIndexed
                    val name = a.selectFirst("img")?.attr("alt")?.ifBlank { null }
                        ?: a.select(".title").text()?.ifBlank { null }
                        ?: a.ownText()?.ifBlank { null }
                        ?: return@forEachIndexed
                    val (season, episodeNum) = parseSeasonEpisode(name, index + 1)
                    episodes.add(
                        newEpisode(epId) {
                            this.name = name.trim()
                            this.season = season
                            this.episode = episodeNum
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot.ifBlank { null }
                this.tags = genreList
                this.duration = getDurationFromString(durationText)
                this.trailers = trailerData
            }
        }
    }

    private fun parseSeasonEpisode(text: String, fallback: Int): Pair<Int, Int> {
        val sxe = Regex("S(\\d+)\\s*E(\\d+)", RegexOption.IGNORE_CASE).find(text)
        if (sxe != null) return Pair(sxe.groupValues[1].toIntOrNull() ?: 1, sxe.groupValues[2].toIntOrNull() ?: fallback)
        val seasonEp = Regex("Season\\s*(\\d+).*?Episode\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)
        if (seasonEp != null) return Pair(
            seasonEp.groupValues[1].toIntOrNull() ?: 1,
            seasonEp.groupValues[2].toIntOrNull() ?: fallback
        )
        val epOnly = Regex("(?:^|[^0-9])E\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)
            ?: Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)
        if (epOnly != null) return Pair(1, epOnly.groupValues[1].toIntOrNull() ?: fallback)
        return Pair(1, fallback)
    }

    // ---------------- Link extraction helpers ----------------
    private fun isVideoUrl(u: String): Boolean {
        val lower = u.lowercase().substringBefore("?")
        return VIDEO_EXT.any { lower.endsWith(it) }
    }

    private fun absoluteUrl(u: String): String = when {
        u.startsWith("http") -> u
        u.startsWith("/") -> mainUrl + u
        else -> "$mainUrl/$u"
    }

    private fun fixImage(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return absoluteUrl(path)
    }

    private fun extractVideoLinks(page: Document): List<String> {
        val links = LinkedHashSet<String>()
        page.select("a[href]").forEach { a ->
            val href = a.attr("href")
            if (isVideoUrl(href)) links.add(absoluteUrl(href))
        }
        page.select("video source, video").forEach { el ->
            val src = el.attr("src").ifBlank { el.attr("data-src") }
            if (src.isNotBlank()) links.add(absoluteUrl(src))
        }
        // generic file paths shown as plain links
        page.select("a[href*='files/']").forEach { a ->
            val href = a.attr("href")
            if (!href.contains("play=")) links.add(absoluteUrl(href))
        }
        return links.filter { it.contains("/files/") || isVideoUrl(it) }.toList()
    }

    // ---------------- loadLinks ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val candidates = LinkedHashSet<String>()

        fun addCandidate(raw: String) {
            val u = raw.trim()
            if (u.isBlank()) return
            if (u.contains("play=")) return // handled separately below
            candidates.add(absoluteUrl(u))
        }

        when {
            data.contains("||") -> data.split("||").forEach { addCandidate(it) }
            data.contains("play=") -> {
                // Resolve the player page again at play time (fresh session)
                val id = data.substringAfter("play=", "").substringBefore("&")
                if (id.isNotBlank()) {
                    val session = getSession()
                    val doc = try {
                        app.get(
                            "$mainUrl/player.php?play=$id&session=$session",
                            headers = getHeaders(),
                            timeout = 30
                        ).document
                    } catch (_: Exception) {
                        null
                    }
                    if (doc != null && doc.select(".modal-dialog, .table").isNotEmpty()) {
                        extractVideoLinks(doc).forEach { addCandidate(it) }
                    }
                    val btn = doc?.selectFirst("a.btn")?.attr("href").orEmpty()
                    if (btn.isNotBlank()) addCandidate(btn)
                }
            }
            else -> addCandidate(data)
        }

        var found = false
        for (c in candidates.distinct()) {
            val quality = qualityFromUrl(c)
            val label = if (quality == Qualities.Unknown.value) name else name + " " + qualityLabel(c)
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = label,
                    url = c
                ) {
                    this.quality = quality
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "User-Agent" to UA,
                        "Referer" to "$mainUrl/"
                    )
                }
            )
            found = true
        }
        return found
    }

    private fun qualityFromUrl(u: String): Int {
        val l = u.lowercase()
        return when {
            "2160p" in l || "4k" in l || "uhd" in l -> Qualities.P2160.value
            "1440p" in l -> Qualities.P1440.value
            "1080p" in l -> Qualities.P1080.value
            "720p" in l -> Qualities.P720.value
            "480p" in l -> Qualities.P480.value
            "360p" in l -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun qualityLabel(u: String): String {
        val l = u.lowercase()
        return when {
            "2160p" in l || "4k" in l || "uhd" in l -> "(4K)"
            "1440p" in l -> "(1440p)"
            "1080p" in l -> "(1080p)"
            "720p" in l -> "(720p)"
            "480p" in l -> "(480p)"
            "360p" in l -> "(360p)"
            else -> "(Auto)"
        }
    }

    class SearchJson : ArrayList<SearchJsonItem>()

    data class SearchJsonItem(
        val id: String?,
        val image: String?,
        val name: String?,
        val type: String?
    )
}
