package com.redowan

import android.util.Base64
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
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * MovieLinkBD — dual-platform provider (CNCVerse port + verified fallback).
 *
 * Platform A — movielinkbd.one (the CNCVerse plugin's proven base, version
 * used by thousands of users):
 *   rotating CDN mirrors (e.g. sqghcr.movielinkbd.li) behind the origin;
 *   cards live on div.movie-item / .item-box / a[href*='/movie/'] …;
 *   posts carry /getLink/<id> (download) and /getWatch/<id> (stream)
 *   anchors. /getLink/ pages contain a /file/<…> one-click direct link
 *   plus external host buttons; /getWatch/ pages embed a video/iframe.
 *
 * Platform B — movielinkbd.is (WordPress, no Cloudflare, verified live
 * 2026-10): a.ml-item-card cards, /page/N/ pagination, posts with
 * /generate/?file=<base64> buttons that sign via dl.vircloud.site/api/sign
 * (Referer https://new.vircloud.site/ + browser UA REQUIRED — empty UAs
 * are 403'd, which is what broke v4's "no link found").
 *
 * The base resolver probes both, skips Cloudflare challenges and remembers
 * which platform the winner speaks (structural detection, not domain
 * trust). Every row/search path is translated per platform.
 *
 * Extra (user request): every source label carries its speed class and a
 * persistent local view counter via SourceStats ("[Fast | 4 views]").
 */
class MovieLinkBDProvider : MainAPI() {

    // .is (WordPress) is the live platform today — .one sits behind a CF wall /
    // parked shell, so it is only a last-resort fallback.
    override var mainUrl = "https://movielinkbd.is"
    override var name = "MovieLinkBD"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.AnimeMovie, TvType.Anime,
    )

    companion object {
        private const val CNC_URL = "https://movielinkbd.one"
        private const val WP_URL = "https://movielinkbd.is"
        private const val HUB_URL = "https://movieslinkbd.com"
        private const val SEARCH_API = "$HUB_URL/wp-json/mlmbd/v1/search?term="
        private const val VIR_REFERER = "https://new.vircloud.site/"
        private const val VIR_SIGN = "https://dl.vircloud.site/api/sign/"

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private val headers = mapOf(
        "User-Agent" to DESKTOP_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8",
    )

    // ----------------------------------------------------------- base resolve

    private enum class Mode { CNC, WP }

    @Volatile
    private var resolved: Pair<String, Mode>? = null

    private fun isCloudflare(html: String, statusCode: Int): Boolean {
        if (statusCode == 403 || statusCode == 503) return true
        if (html.length < 6000) {
            return listOf(
                "cf-browser-verification",
                "Checking if the site connection is secure",
                "Just a moment",
                "Attention Required! | Cloudflare",
                "Enable JavaScript and cookies to continue",
            ).any { html.contains(it, ignoreCase = true) }
        }
        return false
    }

    private fun originOf(url: String): String {
        return try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            WP_URL
        }
    }

    /** Parked / shell page: says "coming soon"-ish and has no real cards. */
    private fun isStubPage(doc: Document, html: String): Boolean {
        if (doc.select("a.ml-item-card, a[href*='/getLink/'], a[href*='generate/?file=']").isNotEmpty()) {
            return false
        }
        val t = html.lowercase()
        return listOf(
            "coming soon", "we will be back", "be right back",
            "site is temporarily", "under maintenance"
        ).any { t.contains(it) }
    }

    private suspend fun detectMode(doc: Document): Mode {
        return when {
            doc.select("a[href*='/getLink/'], a[href*='/getWatch/']").isNotEmpty() -> Mode.CNC
            doc.select("a[href*='/movie/'], a[href*='/series/']").isNotEmpty() &&
                doc.select("a.ml-item-card").isEmpty() -> Mode.CNC
            doc.select("a.ml-item-card").isNotEmpty() ||
                doc.select("a[href*='generate/?file=']").isNotEmpty() -> Mode.WP
            else -> Mode.CNC
        }
    }

    /** Probe both platforms; cache the first one that answers without CF. */
    private suspend fun resolveBase(): Pair<String, Mode> {
        resolved?.let { return it }
        for (cand in listOf(WP_URL to Mode.WP, CNC_URL to Mode.CNC)) {
            try {
                val res = app.get(cand.first, headers = headers, timeout = 25)
                if (res.isSuccessful && !isCloudflare(res.text, res.code)) {
                    val doc = res.document
                    if (isStubPage(doc, res.text)) continue
                    // Platform B may serve an empty-ish home; only accept WP
                    // when its markers exist, otherwise fall back to the
                    // candidate's nominal mode.
                    val mode = if (cand.second == Mode.WP) {
                        if (doc.select("a.ml-item-card, a[href*='generate/?file=']").isNotEmpty() ||
                            doc.select("a[href]").isNotEmpty()
                        ) Mode.WP else Mode.CNC
                    } else detectMode(doc)
                    val pair = originOf(res.url) to mode
                    resolved = pair
                    return pair
                }
            } catch (e: Exception) {
                // try the next candidate
            }
        }
        return WP_URL to Mode.WP
    }

    private fun isWpUrl(url: String): Boolean {
        return url.contains("movielinkbd.is") || url.contains("generate/?file=")
    }

    // -------------------------------------------------------------- fetching

    private suspend fun getDoc(url: String): Document? {
        return try {
            val res = app.get(url, headers = headers, timeout = 60)
            if (res.isSuccessful && !isCloudflare(res.text, res.code)) res.document else null
        } catch (e: Exception) {
            null
        }
    }

    // -------------------------------------------------------------- main page

    /** CNC rows (primary platform), translated for WP when WP wins. */
    override val mainPage = mainPageOf(
        "/" to "Recently Updated",
        "/type/movies" to "All Movies",
        "/type/series" to "All Web Series",
        "/language/hindi" to "Hindi Movies",
        "/language/bangla" to "Bangla Movies",
        "/language/bangla-dubbed" to "Bangla Dubbed",
        "/language/dual-audio" to "Dual Audio",
        "/language/english" to "English",
        "/southIndian" to "South Indian",
        "/language/korean" to "Korean",
        "/anime" to "Anime Zone",
        "/drama" to "K/J/C Drama",
        "/ongoing" to "Ongoing Series",
        "/genre/action" to "Action",
        "/genre/thriller" to "Thriller",
        "/genre/horror" to "Horror",
        "/genre/romance" to "Romance",
        "/category/wwe" to "WWE",
    )

    /** WP category path for a CNC row path (null = use latest). */
    private fun wpPathOf(cncPath: String): String? {
        val p = cncPath.trim('/').lowercase()
        return when {
            p.isEmpty() -> ""
            "bangla-dubbed" in p -> "bangla-dubbed"
            p.endsWith("bangla") -> "bangla"
            "hindi-dubbed" in p -> "hindi-dubbed"
            p.endsWith("hindi") -> "hindi"
            "english" in p -> "english-movies"
            "series" in p -> "web-series"
            "horror" in p -> "horror"
            "korean" in p || "drama" == p -> "web-series"
            "action" in p || "thriller" in p || "romance" in p -> ""
            "anime" in p -> "web-series"
            else -> ""
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val (base, mode) = resolveBase()
        val doc: Document?
        if (mode == Mode.CNC) {
            val path = request.data
            val url = when {
                path == "/" && page == 1 -> "$base/"
                path == "/" -> "$base/page/$page"
                page == 1 -> "$base$path"
                else -> "$base$path/page/$page"
            }
            doc = getDoc(url)
            val items = if (doc != null) parseCncCards(doc, base) else emptyList()
            return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } else {
            val path = wpPathOf(request.data)
            val url = when {
                page <= 1 -> if (path.isNullOrEmpty()) "$base/" else "$base/$path/"
                else -> if (path.isNullOrEmpty()) "$base/page/$page/" else "$base/$path/page/$page/"
            }
            doc = getDoc(url)
            val items = if (doc != null) parseCards(doc) else emptyList()
            return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        }
    }

    // ----------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val (base, mode) = resolveBase()
        if (mode == Mode.CNC) {
            val doc = getDoc("$base/?search=${URLEncoder.encode(q, "UTF-8")}")
                ?: return emptyList()
            return parseCncCards(doc, base)
        }
        return wpSearch(q)
    }

    /** WP search: hub JSON API first (base64 links), /?s= page fallback. */
    private suspend fun wpSearch(query: String): List<SearchResponse> {
        try {
            val res = app.get(
                SEARCH_API + URLEncoder.encode(query, "UTF-8"),
                headers = headers, timeout = 45
            )
            if (res.isSuccessful) {
                val items = mutableListOf<SearchResponse>()
                val arr = org.json.JSONArray(res.text)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val title = obj.optString("title", "").trim()
                    val postUrl = decodeB64(obj.optString("link", "")) ?: continue
                    if (title.isBlank() || !postUrl.startsWith("http")) continue
                    val poster = obj.optString("image", "").ifBlank { null }
                    if (isSeries(postUrl, title)) {
                        items.add(newTvSeriesSearchResponse(title, postUrl, TvType.TvSeries) { this.posterUrl = poster })
                    } else {
                        items.add(newMovieSearchResponse(title, postUrl, TvType.Movie) { this.posterUrl = poster })
                    }
                }
                if (items.isNotEmpty()) return items.distinctBy { it.url }
            }
        } catch (e: Exception) {
            // fall back to HTML search page
        }
        val doc = getDoc("$WP_URL/?s=" + URLEncoder.encode(query, "UTF-8"))
            ?: return emptyList()
        return parseCards(doc)
    }

    private fun decodeB64(raw: String): String? {
        val s = raw.trim().replace(" ", "+")
        if (s.isEmpty()) return null
        return try {
            String(Base64.decode(s, Base64.DEFAULT)).trim().ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ cards

    /** Platform B (WordPress) cards: a.ml-item-card. */
    private fun parseCards(doc: Document): List<SearchResponse> {
        return doc.select("a.ml-item-card, div.ml-item-card a[href]").mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.isBlank() || !href.startsWith("http")) return@mapNotNull null
            if (href.contains("/generate/") || href.contains("#")) return@mapNotNull null
            var raw = a.attr("title")
            if (raw.isBlank()) {
                val t = a.selectFirst(".ml-item-title")
                if (t != null) raw = t.text()
            }
            val title = raw.replace(Regex("\\s+"), " ").trim()
            if (title.isEmpty()) return@mapNotNull null
            val img = a.selectFirst("img")
            val poster: String? = if (img == null) null else img.attr("data-src")
                .ifBlank { img.attr("src") }
                .ifBlank { null }
            if (isSeries(href, title)) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }.distinctBy { it.url }
    }

    /** Platform A (CNCVerse) cards with the multi-fallback card scanner. */
    private fun parseCncCards(doc: Document, base: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cards = doc.select("div.movie-item, div.item-box, div.film-item, div.post-item, .movie-card")

        if (cards.isNotEmpty()) {
            for (card in cards) {
                val aTag = card.selectFirst(
                    "a[href*='/movie/'], a[href*='/series/'], a[href*='/anime/'], a[href*='/download18plus/']"
                ) ?: continue
                val href = aTag.attr("abs:href").ifBlank {
                    val h = aTag.attr("href")
                    if (h.startsWith("http")) h else base + h
                }
                val title = (card.selectFirst(".title, .movie-title, h3, h2")?.text()?.trim()
                    ?: aTag.attr("title").trim()).replace(Regex("\\s+"), " ")
                if (title.isEmpty()) continue
                val img = card.selectFirst("img")
                val poster = img?.attr("data-src")?.ifBlank { img.attr("src") } ?: img?.attr("src")
                val type = if (href.contains("/series/") || href.contains("/anime/")) TvType.TvSeries else TvType.Movie
                results.add(newMovieSearchResponse(title, href, type) { this.posterUrl = poster })
            }
            if (results.isNotEmpty()) return results.distinctBy { it.url }
        }

        // Fallback: /movie/ //series/ //anime/ anchors that wrap a poster img.
        val movieLinkPattern =
            "a[href*='/movie/'], a[href*='/series/'], a[href*='/anime/'], a[href*='/download18plus/']"
        val seen = mutableSetOf<String>()
        doc.select(movieLinkPattern).forEach { a ->
            val href = a.attr("abs:href").ifBlank {
                val h = a.attr("href")
                if (h.startsWith("http")) h else base + h
            }
            if (!seen.add(href)) return@forEach
            val img = a.selectFirst("img") ?: return@forEach
            val poster = img.attr("data-src").ifBlank { img.attr("src") }
            val titleEl = a.parent()?.selectFirst(".title, .movie-title, h3, h2, [class*='name']")
            val title = (titleEl?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: a.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: a.text().trim().takeIf { it.isNotEmpty() }
                ?: return@forEach).replace(Regex("\\s+"), " ")
            if (title.length < 4) return@forEach
            val type = if (href.contains("/series/") || href.contains("/anime/")) TvType.TvSeries else TvType.Movie
            results.add(newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster.takeIf { it.isNotEmpty() && poster.startsWith("http") }
            })
        }

        // Last resort: plain text title links (skip nav items).
        if (results.isEmpty()) {
            doc.select(movieLinkPattern).forEach { a ->
                val href = a.attr("abs:href").ifBlank {
                    val h = a.attr("href")
                    if (h.startsWith("http")) h else base + h
                }
                if (!seen.add(href)) return@forEach
                val title = a.text().trim().takeIf { it.isNotEmpty() } ?: return@forEach
                if (title.length < 4 || title.all { it.isUpperCase() || it == ' ' }) return@forEach
                val type = if (href.contains("/series/") || href.contains("/anime/")) TvType.TvSeries else TvType.Movie
                results.add(newMovieSearchResponse(title, href, type))
            }
        }
        return results.distinctBy { it.url }
    }

    private fun isSeries(url: String, title: String): Boolean {
        val t = "$url $title".lowercase()
        return t.contains("season") || t.contains("web-series") ||
            Regex("(?i)\\bepisodes?[-\\s]?\\d|s\\d{1,2}\\s?e\\d{1,3}\\b|all-episodes").containsMatchIn(t)
    }

    // ------------------------------------------------------------------ load

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MlbdFile(val file: String? = null, val label: String? = null, val quality: Int = 0)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MlbdSource(val url: String? = null, val label: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MlbdPayload(
        val files: List<MlbdFile> = emptyList(),
        val downloads: List<MlbdSource> = emptyList(),
    )

    private fun MlbdPayload.toJson(): String = mapper.writeValueAsString(this)

    /** CNC data format: "quality|url ; quality|url". */
    private data class QLink(val quality: String, val url: String) {
        fun pack(): String = "$quality|$url"
    }

    override suspend fun load(url: String): LoadResponse {
        return if (isWpUrl(url)) loadWp(url) else loadCnc(url)
    }

    /** Platform A detail page (CNCVerse flow). */
    private suspend fun loadCnc(url: String): LoadResponse {
        val doc = getDoc(url) ?: throw IllegalArgumentException("post unreachable: $url")

        val rawTitle = doc.selectFirst("h1, .movie-title, .film-title, [class*='title']")?.text()
            ?.replace(Regex("\\s+"), " ")?.trim()
            ?: doc.title().substringBefore("•").trim().ifBlank { url }
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val posterEl = doc.selectFirst(
            "img.poster, img[class*='poster'], .poster img, .thumb img, img[src*='poster'], img[src*='uploads']"
        )
        val poster: String? = if (posterEl != null) {
            posterEl.attr("data-src").ifBlank { posterEl.attr("src") }.takeIf { it.isNotEmpty() }
        } else null
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        fun metaVal(label: String): String? {
            return doc.select("li, p, span, div").firstOrNull { el ->
                el.text().contains(label, ignoreCase = true)
            }?.text()?.substringAfter(":")?.trim()?.takeIf { it.isNotEmpty() }
        }

        val plot = doc.selectFirst(".storyline p, .storyline, [class*='story'] p, [class*='plot']")
            ?.text()?.trim()?.ifBlank { null } ?: metaVal("Storyline")
        val genre = metaVal("Genre")
        val cast = metaVal("Cast")
        val language = metaVal("Language")
        val rating = doc.selectFirst("[class*='imdb'], [class*='rating']")?.text()
            ?.let { Regex("[0-9]+\\.?[0-9]*").find(it)?.value?.toFloatOrNull() }
        val ratingNote = rating?.let { "IMDb: $it" }

        val fullPlot = buildString {
            language?.let { append("Language: $it\n") }
            genre?.let { append("Genre: $it\n") }
            cast?.let { append("Cast: $it\n") }
            ratingNote?.let { append("$it\n") }
            plot?.let { append("\n$it") }
        }.trim().ifBlank { null }

        val isSeriesUrl = url.contains("/series/") || url.contains("/anime/")

        val linkAnchors = doc.select("a[href*='/getLink/']")
        val watchAnchors = doc.select("a[href*='/getWatch/']")

        if (!isSeriesUrl) {
            val linksData = (linkAnchors + watchAnchors).mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank {
                    val h = a.attr("href")
                    if (h.startsWith("http")) h else "$mainUrl$h"
                }
                if (!href.startsWith("http")) return@mapNotNull null
                "${extractQualityLabel(a.text())}|$href"
            }.joinToString(" ; ")

            return newMovieLoadResponse(rawTitle, url, TvType.Movie, linksData) {
                this.posterUrl = poster
                this.year = year
                this.plot = fullPlot
            }
        }

        // Series: group anchors by episode sections, else one batch episode.
        val episodesData = mutableListOf<Episode>()
        val episodeSections = doc.select(
            "div.episode-section, div.season-section, h3:contains(Episode), h4:contains(Episode), " +
                "div[class*='episode'], div[class*='season'], strong:contains(Ep), b:contains(Ep)"
        )

        for (section in episodeSections) {
            val sectionText = section.text()
            val epRange = Regex("(?:Ep|Episode)[^\\d]*(\\d+)(?:[^\\d]+(\\d+))?", RegexOption.IGNORE_CASE)
                .find(sectionText)
            val start = epRange?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val end = epRange?.groupValues?.get(2)?.toIntOrNull() ?: start

            val sectionLinks = mutableListOf<String>()
            var sib = section.nextElementSibling()
            while (sib != null && !sib.tagName().matches(Regex("h[1-6]"))) {
                sib.select("a[href*='/getLink/'], a[href*='/getWatch/']").forEach { a ->
                    val href = a.attr("abs:href").ifBlank {
                        val h = a.attr("href")
                        if (h.startsWith("http")) h else "$mainUrl$h"
                    }
                    if (href.startsWith("http")) sectionLinks.add("${extractQualityLabel(a.text())}|$href")
                }
                sib = sib.nextElementSibling()
            }

            if (sectionLinks.isNotEmpty()) {
                val epUrl = sectionLinks.joinToString(" ; ")
                for (epNum in start..end) {
                    episodesData.add(newEpisode(epUrl) {
                        name = "Episode $epNum"
                        this.season = 1
                        this.episode = epNum
                    })
                }
            }
        }

        if (episodesData.isEmpty() && (linkAnchors.isNotEmpty() || watchAnchors.isNotEmpty())) {
            val allLinks = (linkAnchors + watchAnchors).mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank {
                    val h = a.attr("href")
                    if (h.startsWith("http")) h else "$mainUrl$h"
                }
                if (!href.startsWith("http")) return@mapNotNull null
                "${extractQualityLabel(a.text())}|$href"
            }.joinToString(" ; ")

            episodesData.add(newEpisode(allLinks) {
                name = "Full Season"
                this.season = 1
                this.episode = 1
            })
        }

        return newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, episodesData) {
            this.posterUrl = poster
            this.year = year
            this.plot = fullPlot
        }
    }

    /** Platform B detail page (WordPress /generate/ flow — v4 logic). */
    private suspend fun loadWp(url: String): LoadResponse {
        val doc = getDoc(url) ?: throw IllegalArgumentException("post unreachable: $url")

        val title = doc.selectFirst("h1.ml-sh-title")?.text()?.replace(Regex("\\s+"), " ")?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.title().trim().ifBlank { url }
        val poster: String? = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.ifBlank { null }
            ?: doc.selectFirst(".ml-sh-content img, .ml-item-thumb img")?.attr("data-src")
                ?.ifBlank { null }
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()
        val tags = doc.select(".ml-sh-cats a, .ml-cat-badge").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct().take(6).ifEmpty { null }

        val sections = LinkedHashMap<String, MutableList<MlbdFile>>()
        var current = "Full"
        for (el in doc.select("h2, h3, h4, h5, h6, a[href*='generate/?file='], a[href*='/generate/']")) {
            if (el.tagName() != "a") {
                val head = el.text().replace(Regex("\\s+"), " ").trim()
                if (head.isNotBlank() && !head.equals(title, ignoreCase = true)) {
                    current = head
                    sections.getOrPut(current) { mutableListOf() }
                }
                continue
            }
            val href = el.attr("abs:href").ifBlank { el.attr("href") }
            val b64 = Regex("generate/\\?file=([A-Za-z0-9+/=]+)").find(href)?.groupValues?.get(1)
                ?: continue
            val fname = decodeB64(b64) ?: continue
            val bucket = sections.getOrPut(current) { mutableListOf() }
            if (bucket.none { it.file == b64 }) {
                bucket.add(MlbdFile(b64, prettyFileName(fname), mlbdQualityNum(fname)))
            }
        }

        val downloads = doc.select("a[href]").mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (!href.startsWith("http") || !isKnownHost(href)) return@mapNotNull null
            val text = a.text().replace(Regex("\\s+"), " ").trim().ifBlank { "Download" }
            MlbdSource(href, text)
        }.distinctBy { it.url?.substringBefore("?") }

        val epRegex = Regex("(?i)episodes?[-\\s]?\\d|ep[-\\s]?\\d{1,3}|পর্ব")
        val files = sections.values.flatten().distinctBy { it.file }
        val isSeriesPost = isSeries(url, title) ||
            (sections.keys.count { epRegex.containsMatchIn(it) } >= 1 && sections.size >= 2)

        return if (isSeriesPost && sections.isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            sections.forEach { (secTitle, links) ->
                if (links.isEmpty()) return@forEach
                val epNum = Regex("(?i)ep(?:isodes?)?[-\\s]?(\\d{1,3})").find(secTitle)
                    ?.groupValues?.get(1)?.toIntOrNull()
                val qualities = links.mapNotNull { it.quality.takeIf { q -> q > 0 } }
                    .distinct().sortedDescending().joinToString("/") { "${it}p" }
                val data = MlbdPayload(links, downloads).toJson()
                episodes.add(newEpisode(data) {
                    name = if (secTitle == "Full") "Full" + if (qualities.isNotBlank()) " · $qualities" else ""
                    else secTitle + if (qualities.isNotBlank()) " · $qualities" else ""
                    if (epNum != null) this.episode = epNum
                    this.season = 1
                })
            }
            if (episodes.isEmpty()) {
                episodes.add(newEpisode(MlbdPayload(files, downloads).toJson()) {
                    name = "Full"
                    this.episode = 1
                    this.season = 1
                })
            }
            episodes.sortBy { it.episode }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            val data = MlbdPayload(files, downloads).toJson()
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    /** "MoviesLinkBD.com-Aryabhatt-Ka-Zero-2026-Hindi-ORG-720p.mkv" -> "Aryabhatt Ka Zero 2026 Hindi ORG 720p". */
    private fun prettyFileName(fname: String): String {
        return fname.substringBeforeLast('.')
            .removePrefix("MoviesLinkBD.com-").removePrefix("MovieLinkBD.com-")
            .replace('-', ' ').replace('_', ' ')
            .replace(Regex("\\s+"), " ").trim()
    }

    private fun isKnownHost(link: String): Boolean {
        val l = link.lowercase()
        return listOf(
            "gdflix", "gdxfiles", "hubcloud", "hubdrive", "multicloud", "filepress",
            "filebee", "pixeldrain", "gofile", "filesdl", "driveseed", "driveleech",
            "savelinks", "m4ulinks",
        ).any { it in l }
    }

    private fun extractQualityLabel(text: String): String {
        return when {
            text.contains("4K", true) || text.contains("2160", true) -> "4K"
            text.contains("1080", true) -> "1080p"
            text.contains("720", true) -> "720p"
            text.contains("480", true) -> "480p"
            text.contains("360", true) -> "360p"
            text.contains("Watch Online", true) -> "Stream"
            text.contains("Download", true) -> "Download"
            else -> text.take(30).trim().ifEmpty { "Unknown" }
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
        return if (data.trimStart().startsWith("{")) {
            loadLinksWp(data, subtitleCallback, callback)
        } else {
            loadLinksCnc(data, subtitleCallback, callback)
        }
    }

    /** Platform A: getLink/getWatch/file resolution (CNCVerse flow). */
    private suspend fun loadLinksCnc(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.contains("|")) return false
        var found = false
        for (item in data.split(" ; ")) {
            val parts = item.split("|", limit = 2)
            val qualityLabel = parts.getOrNull(0)?.trim() ?: ""
            val linkUrl = parts.getOrNull(1)?.trim() ?: item.trim()
            if (linkUrl.isEmpty() || !linkUrl.startsWith("http")) continue
            try {
                when {
                    linkUrl.contains("/getLink/") -> {
                        if (resolveGetLink(linkUrl, qualityLabel, subtitleCallback, callback)) found = true
                    }
                    linkUrl.contains("/getWatch/") -> {
                        if (resolveGetWatch(linkUrl, qualityLabel, callback)) found = true
                    }
                    linkUrl.contains("/file/") -> {
                        emitDirect(
                            linkUrl, "$name [$qualityLabel]", "movielinkbd file",
                            mlbdQualityNum(qualityLabel), mainUrl, callback
                        )
                        found = true
                    }
                    else -> {
                        if (MlbdExtractors.resolve(
                                linkUrl, name, mainUrl, subtitleCallback, callback
                            )
                        ) found = true
                    }
                }
            } catch (e: Exception) {
                // next item
            }
        }
        return found
    }

    private suspend fun emitDirect(
        url: String,
        label: String,
        hostKey: String,
        quality: Int,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        SourceStats.record(hostKey)
        callback(
            newExtractorLink(
                name,
                SourceStats.decorate(hostKey, label),
                url,
                ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
                this.referer = referer
            }
        )
    }

    /** /getLink/<id> -> /file/ direct link + external host buttons. */
    private suspend fun resolveGetLink(
        getLinkUrl: String,
        qualityLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = getDoc(getLinkUrl) ?: return false
            var found = false

            // ONE CLICK DOWNLOAD -> /file/... direct link
            val fileAnchor = doc.selectFirst("a[href*='/file/']")
            if (fileAnchor != null) {
                val fileUrl = fileAnchor.attr("abs:href").ifBlank { fileAnchor.attr("href") }
                if (fileUrl.startsWith("http")) {
                    emitDirect(
                        fileUrl, "$name [$qualityLabel]", "movielinkbd file",
                        mlbdQualityNum(qualityLabel), getLinkUrl, callback
                    )
                    found = true
                }
            }

            // External host buttons on the same page.
            for (a in doc.select("a[href]")) {
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                if (!href.startsWith("http")) continue
                if (href.contains("movielinkbd") || href.contains("telegram") ||
                    href.contains("t.me") || href.contains("google.com/store") ||
                    href.contains("/file/")
                ) continue
                if (isKnownHost(href)) {
                    try {
                        if (MlbdExtractors.resolve(href, name, getLinkUrl, subtitleCallback, callback)) {
                            found = true
                        }
                    } catch (e: Exception) { /* next */ }
                }
            }
            found
        } catch (e: Exception) {
            false
        }
    }

    /** /getWatch/<id> -> embedded video/iframe stream. */
    private suspend fun resolveGetWatch(
        getWatchUrl: String,
        qualityLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = getDoc(getWatchUrl) ?: return false
            val videoSrc = doc.selectFirst("video source, video[src], source[src]")?.attr("abs:href")
                ?.ifBlank { doc.selectFirst("video source, video[src], source[src]")?.attr("src") }
                ?: doc.selectFirst("iframe[src]")?.attr("abs:href")
                    ?.ifBlank { doc.selectFirst("iframe[src]")?.attr("src") }
            if (videoSrc.isNullOrBlank() || !videoSrc.startsWith("http")) return false
            val quality = mlbdQualityNum(qualityLabel)
            SourceStats.record("movielinkbd watch")
            callback(
                ExtractorLink(
                    source = name,
                    name = SourceStats.decorate(
                        "movielinkbd watch", "$name [Stream] $qualityLabel".trim()
                    ),
                    url = videoSrc,
                    referer = getWatchUrl,
                    quality = quality,
                    type = if (videoSrc.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    headers = emptyMap()
                )
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Platform B: vircloud-signed direct links + stray host links (v4). */
    private suspend fun loadLinksWp(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = tryParseJson<MlbdPayload>(data) ?: return false
        var found = false

        // 1) vircloud signed direct links — one per file/quality.
        for (f in payload.files) {
            val b64 = f.file?.trim() ?: continue
            if (b64.isEmpty()) continue
            val dlUrl = signVircloud(b64) ?: continue
            val q = f.quality.takeIf { it > 0 } ?: mlbdQualityNum(f.label ?: "")
            val label = f.label?.ifBlank { null } ?: "Stream"
            emitDirect(
                dlUrl, "$name ${qLabel(q)}· $label".trim(), "vircloud",
                q, VIR_REFERER, callback
            )
            found = true
        }

        // 2) Stray external host links (older posts).
        for (dl in payload.downloads) {
            val link = dl.url ?: continue
            try {
                if (MlbdExtractors.resolve(link, name, WP_URL, subtitleCallback, callback)) found = true
            } catch (e: Exception) {
                // next host
            }
        }
        return found
    }

    /**
     * dl.vircloud.site/api/sign/<b64> -> {"downloadUrl": "…download/<b64>?exp=…&sig=…"}
     * The Referer AND a browser UA are required — empty/okhttp-default UAs
     * get 403 (the root cause of v4's "no link found"). Verified live.
     */
    private suspend fun signVircloud(fileB64: String): String? {
        return try {
            val res = app.get(
                VIR_SIGN + fileB64,
                headers = mapOf(
                    "User-Agent" to DESKTOP_UA,
                    "Referer" to VIR_REFERER,
                    "Accept" to "application/json",
                ),
                timeout = 30
            )
            if (!res.isSuccessful) return null
            Regex("\"downloadUrl\"\\s*:\\s*\"([^\"]+)\"").find(res.text)
                ?.groupValues?.get(1)?.replace("\\/", "/")
                ?.takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            null
        }
    }
}

private fun qLabel(q: Int): String = if (q > 0) "${q}p" else ""
