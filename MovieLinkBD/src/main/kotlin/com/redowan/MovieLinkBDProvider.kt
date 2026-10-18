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
import android.util.Base64
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * MovieLinkBD (movielinkbd.is — "Largest Movie Download Site in Bangladesh").
 *
 * Verified live architecture (2026-10):
 *  - The public hub movieslinkbd.com is a WordPress shell whose posts are
 *    stubs; it exposes /wp-json/mlmbd/v1/search?term= which returns cards
 *    whose base64 "link" field points at the REAL site: movielinkbd.is.
 *  - movielinkbd.is is plain WordPress (no Cloudflare gate): home rows and
 *    category pages (/bangla/, /hindi/ …) paginate as /page/N/ and list
 *    a.ml-item-card anchors (title attribute + lazy data-src poster).
 *  - Post pages contain /generate/?file=<base64 filename> buttons. The
 *    /generate/ page simply forwards to https://new.vircloud.site/?file=<b64>,
 *    whose front-end calls https://dl.vircloud.site/api/sign/<b64>
 *    (Referer: https://new.vircloud.site/ required) -> signed downloadUrl:
 *    https://dl.vircloud.site/download/<b64>?exp=…&sig=…
 *  - The signed /download/ URL serves raw video bytes (MKV, HTTP 206) when
 *    played with a normal app User-Agent; only empty/curl-style UAs are
 *    challenged. ExoPlayer default UA passes — verified live.
 *
 * loadLinks() therefore skips the /generate/ hop entirely and signs every
 * file directly against dl.vircloud.site, emitting one link per quality
 * variant (720p/1080p/4K …). Any stray external host links found on the
 * post page are dispatched through the shared MlbdExtractors.
 */
class MovieLinkBDProvider : MainAPI() {

    override var mainUrl = "https://movielinkbd.is"
    override var name = "MovieLinkBD"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    companion object {
        private const val HUB_URL = "https://movieslinkbd.com"
        private const val SEARCH_API = "$HUB_URL/wp-json/mlmbd/v1/search?term="
        private const val VIR_REFERER = "https://new.vircloud.site/"
        private const val VIR_SIGN = "https://dl.vircloud.site/api/sign/"
    }

    private val headers = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8",
    )

    /** Latest first, then the priority language rows requested by users. */
    override val mainPage = mainPageOf(
        "" to "Latest Movies",
        "/bangla/" to "Bangla Movies",
        "/bangla-dubbed/" to "Bangla Dubbed",
        "/hindi/" to "Hindi Movies",
        "/hindi-dubbed/" to "Hindi Dubbed",
        "/english-movies/" to "English Movies",
        "/tamil/" to "Tamil Movies",
        "/telugu/" to "Telugu Movies",
        "/malayalam/" to "Malayalam Movies",
        "/kannada/" to "Kannada Movies",
        "/web-series/" to "Web Series",
        "/horror/" to "Horror Movies",
        "/mcu/" to "MCU Movies",
    )

    // -------------------------------------------------------------- fetching

    private suspend fun getDoc(url: String): Document? {
        return try {
            val res = app.get(url, headers = headers, timeout = 60)
            if (res.isSuccessful) res.document else null
        } catch (e: Exception) {
            null
        }
    }

    // ----------------------------------------------------------------- cards

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

    private fun isSeries(url: String, title: String): Boolean {
        val t = "$url $title".lowercase()
        return t.contains("season") || t.contains("web-series") ||
            Regex("(?i)\\bepisodes?[-\\s]?\\d|s\\d{1,2}\\s?e\\d{1,3}\\b|all-episodes").containsMatchIn(t)
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
        val doc = getDoc(url)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = parseCards(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        // Primary: the hub's JSON search API (base64 links to movielinkbd.is).
        try {
            val res = app.get(
                SEARCH_API + URLEncoder.encode(q, "UTF-8"),
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

        // Fallback: WordPress search results page.
        val doc = getDoc("$mainUrl/?s=" + URLEncoder.encode(q, "UTF-8"))
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

    override suspend fun load(url: String): LoadResponse {
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

        // Walk headings + generate links in document order.
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

        // Legacy/stray external host links on the post page.
        val downloads = doc.select("a[href]").mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (!href.startsWith("http")) return@mapNotNull null
            if (!isKnownHost(href)) return@mapNotNull null
            val text = a.text().replace(Regex("\\s+"), " ").trim().ifBlank { "Download" }
            MlbdSource(href, text)
        }.distinctBy { it.url?.substringBefore("?") }

        val epRegex = Regex("(?i)episodes?[-\\s]?\\d|ep[-\\s]?\\d{1,3}|পর্ব")
        val files = sections.values.flatten().distinctBy { it.file }
        val isSeries = isSeries(url, title) ||
            (sections.keys.count { epRegex.containsMatchIn(it) } >= 1 && sections.size >= 2)

        return if (isSeries && sections.isNotEmpty()) {
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
            "savelinks",
        ).any { it in l }
    }

    // ------------------------------------------------------------- loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val payload = tryParseJson<MlbdPayload>(data) ?: return false
        var found = false

        // 1) vircloud signed direct links — one per file/quality.
        for (f in payload.files) {
            val b64 = f.file?.trim() ?: continue
            if (b64.isEmpty()) continue
            val dlUrl = signVircloud(b64) ?: continue
            val q = f.quality.takeIf { it > 0 } ?: mlbdQualityNum(f.label ?: "")
            val label = f.label?.ifBlank { null } ?: "Stream"
            callback(
                newExtractorLink(
                    name,
                    "$name ${qLabel(q)}· $label".trim(),
                    dlUrl,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = q
                    this.referer = VIR_REFERER
                }
            )
            found = true
        }

        // 2) Stray external host links (older posts).
        for (dl in payload.downloads) {
            val link = dl.url ?: continue
            try {
                MlbdExtractors.resolve(link, name, mainUrl, subtitleCallback, callback)
                found = true
            } catch (e: Exception) {
                // next host
            }
        }
        return found
    }

    /**
     * dl.vircloud.site/api/sign/<b64> -> {"downloadUrl": "https://dl.vircloud.site/download/<b64>?exp=…&sig=…"}
     * The Referer header is required; the signed URL is valid for a few hours
     * and serves raw video bytes (HTTP 206) to normal app User-Agents.
     */
    private suspend fun signVircloud(fileB64: String): String? {
        return try {
            val res = app.get(
                VIR_SIGN + fileB64,
                headers = mapOf(
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
