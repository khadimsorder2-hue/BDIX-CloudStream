package com.redowan

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
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
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * MovieLinkBD (movieslinkbd.com family: movielinkbd.is, movielink.ch, movielinkbd.net).
 *
 * The site family runs on WordPress with two open JSON surfaces:
 *  - /wp-json/wp/v2/posts?categories=<id>&page=N&_embed=wp:featuredmedia  (catalogs)
 *  - /wp-json/mlmbd/v1/search?term=<q>                                    (live search)
 *
 * Every post page carries download buttons of the shape
 *   /generate/?file=<base64-of-filename>
 * The filename (e.g. "MLMBD.com-Title-2026-Bengali-720p.mkv") encodes the
 * quality, container and episode numbers.  Signing the same base64 token
 * against https://dl.vircloud.site/api/sign/<b64> returns {downloadUrl} —
 * a direct, ungated CDN link that is valid for ~6 hours.
 */
class MovieLinkBDProvider : MainAPI() {
    override var mainUrl = "https://movieslinkbd.com"
    override var name = "MovieLinkBD"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)

    /** Post pages currently resolve on the .is mirror. */
    private val postUrls = listOf("https://movielinkbd.is", "https://movieslinkbd.com")

    /** Worker that turns file tokens into signed direct CDN links. */
    private val signApi = "https://dl.vircloud.site/api/sign/"

    /** movieslinkbd.com family sits behind Cloudflare — kill challenges
     *  transparently (same pattern as the working Krx18). */
    private val cfKiller = CloudflareKiller()

    private val docHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept-Language" to "bn,en-US;q=0.9,en;q=0.8",
    )

    override val mainPage = mainPageOf(
        "96" to "Bangla Movies",
        "1" to "Hindi Movies",
        "91" to "Hindi Dubbed",
        "95" to "Dual Audio",
        "94" to "English Movies",
        "107" to "Bangla Dubbed",
        "93" to "Tamil Movies",
        "101" to "Malayalam Movies",
        "99" to "Kannada Movies",
        "105" to "Horror Movies",
        "117" to "MCU Movies",
        "112" to "K-Drama",
        "113" to "Korean",
        "97" to "Web Series",
        "104" to "Chinese",
        "103" to "Japanese",
        "108" to "Animation",
        "109" to "Others",
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpPost(
        val id: Long? = null,
        val link: String? = null,
        val title: WpTitle? = null,
        @JsonProperty("_embedded") val embedded: WpEmbedded? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpTitle(val rendered: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpMedia(val source_url: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpEmbedded(val media: List<WpMedia>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MlmbdItem(
        val title: String? = null,
        val link: String? = null,
        val image: String? = null,
    )

    /** Signed URL returned by the vircloud worker. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SignResponse(val downloadUrl: String? = null)

    /** File token passed from load() to loadLinks(). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MlbdFileData(val b64: String? = null, val name: String? = null)

    private fun decodeHtml(text: String?): String? = text
        ?.replace("&#038;", "&")
        ?.replace("&amp;", "&")
        ?.replace("&#8211;", "-")
        ?.replace("&#8217;", "'")
        ?.replace("&quot;", "\"")
        ?.replace("&#8220;", "\"")
        ?.replace("&#8221;", "\"")
        ?.trim()

    /** wp-json REST catalog with embedded posters. */
    private suspend fun wpCatalog(categoryId: String, page: Int): List<SearchResponse> {
        val url = "$mainUrl/wp-json/wp/v2/posts?categories=$categoryId" +
                "&per_page=20&page=$page&_embed=wp:featuredmedia"
        val text = try {
            app.get(url, headers = docHeaders, timeout = 30, interceptor = cfKiller).text
        } catch (e: Exception) {
            return emptyList()
        }
        val posts = tryParseJson<List<WpPost>>(text) ?: return emptyList()
        return posts.mapNotNull { post ->
            val link = post.link ?: return@mapNotNull null
            val title = decodeHtml(post.title?.rendered) ?: return@mapNotNull null
            val poster = post.embeddedMedia()?.source_url
            toSearchResponse(title, link, poster)
        }
    }

    private fun WpPost.embeddedMedia(): WpMedia? = embedded?.media?.firstOrNull()

    private fun toSearchResponse(title: String, url: String, poster: String?): SearchResponse {
        val isSeries = seriesHint(title)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        }
    }

    private fun seriesHint(text: String): Boolean = listOf(
        "season", "web series", "webseries", "s0", "s1", "episode", "ep "
    ).any { text.contains(it, ignoreCase = true) }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = wpCatalog(request.data, page)
        // The REST API returns fewer rows than per_page only on the last page,
        // and a 400 for out-of-range pages which maps to an empty list above.
        val hasNext = items.size >= 20
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        // 1) The family's own live-search endpoint: title + base64 link + image.
        val results = mutableListOf<SearchResponse>()
        try {
            val apiUrl = "$mainUrl/wp-json/mlmbd/v1/search?term=" + URLEncoder.encode(q, "UTF-8")
            val text = app.get(apiUrl, headers = docHeaders + mapOf(
                "Accept" to "application/json",
                "Referer" to "$mainUrl/",
            ), timeout = 30, interceptor = cfKiller).text
            val items = tryParseJson<List<MlmbdItem>>(text).orEmpty()
            for (item in items) {
                val title = decodeHtml(item.title) ?: continue
                val link = decodeSearchLink(item.link) ?: continue
                results.add(toSearchResponse(title, link, item.image))
            }
        } catch (e: Exception) {
            // fall through to the REST search
        }
        if (results.isNotEmpty()) return results.distinctBy { it.url }

        // 2) Plain WordPress REST search (covers posts the live search misses).
        try {
            val apiUrl = "$mainUrl/wp-json/wp/v2/posts?search=" + URLEncoder.encode(q, "UTF-8") +
                    "&per_page=20&_embed=wp:featuredmedia"
            val text = app.get(apiUrl, headers = docHeaders, timeout = 30, interceptor = cfKiller).text
            val posts = tryParseJson<List<WpPost>>(text).orEmpty()
            for (post in posts) {
                val link = post.link ?: continue
                val title = decodeHtml(post.title?.rendered) ?: continue
                val poster = post.embeddedMedia()?.source_url
                results.add(toSearchResponse(title, link, poster))
            }
        } catch (e: Exception) {
            // ignore
        }
        return results.distinctBy { it.url }
    }

    /** The live-search "link" field is a base64 URL pointing at the .is mirror. */
    private fun decodeSearchLink(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return try {
            val decoded = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
            decoded.takeIf { it.startsWith("http") } ?: value
        } catch (e: Exception) {
            value.takeIf { it.startsWith("http") }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = fetchFirst(url)
        val doc = res.doc
        val html = res.text

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: decodeHtml(doc.title())
            ?: url
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("article img, .post img, img[src*=uploads]")?.attr("src")

        // Collect every unique /generate/?file=<b64> token on the page.
        val files = mutableListOf<Pair<String, String>>() // b64 -> filename
        val seenB64 = mutableSetOf<String>()
        for (a in doc.select("a[href*=generate]")) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            val b64 = Regex("[?&]file=([A-Za-z0-9+/=]{16,})").find(href)?.groupValues?.get(1)
                ?: continue
            if (!seenB64.add(b64)) continue
            files.add(b64 to decodeFileName(b64))
        }
        // Some layouts inline the token in data attributes or plain links.
        if (files.isEmpty()) {
            Regex("[?&]file=([A-Za-z0-9+/=]{16,})").findAll(html).forEach { m ->
                val b64 = m.groupValues[1]
                if (seenB64.add(b64)) files.add(b64 to decodeFileName(b64))
            }
        }

        val isSeries = seriesHint(title) || Regex("episode[-_ ]?\\d+", RegexOption.IGNORE_CASE)
            .containsMatchIn(files.joinToString(" ") { it.second })

        return if (isSeries && files.any { episodeOf(it.second) != null || epRangeOf(it.second) != null }) {
            val single = sortedMapOf<Int, MutableList<Pair<String, String>>>()
            val batches = mutableListOf<Triple<Int, Int, Pair<String, String>>>()
            for (file in files) {
                val singleEp = episodeOf(file.second)
                val range = epRangeOf(file.second)
                when {
                    singleEp != null -> single.getOrPut(singleEp) { mutableListOf() }.add(file)
                    range != null -> batches.add(Triple(range.first, range.second, file))
                    else -> single.getOrPut(1) { mutableListOf() }.add(file)
                }
            }
            val episodes = mutableListOf<Episode>()
            batches.sortedBy { it.first }.forEach { (lo, hi, file) ->
                episodes.add(newEpisode(MlbdFileData(file.first, file.second).toJson()) {
                    name = "E%02d-E%02d · %s".format(lo, hi, qualityOf(file.second))
                    this.episode = lo
                    this.season = 1
                })
            }
            single.forEach { (num, links) ->
                episodes.add(newEpisode(
                    links.joinToString("|") { MlbdFileData(it.first, it.second).toJson() }
                ) {
                    name = "Episode $num" + if (links.size == 1) " · ${qualityOf(links[0].second)}" else ""
                    this.episode = num
                    this.season = 1
                })
            }
            episodes.sortBy { it.episode }
            newTvSeriesLoadResponse(title, res.url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
            }
        } else {
            val data = files.joinToString("|") { MlbdFileData(it.first, it.second).toJson() }
            newMovieLoadResponse(title, res.url, TvType.Movie, data) {
                this.posterUrl = poster
            }
        }
    }

    /** Post pages may sit on either domain — try them in order. */
    private data class FetchedPage(
        val text: String,
        val url: String,
        val doc: org.jsoup.nodes.Document,
    )

    private suspend fun fetchFirst(url: String): FetchedPage {
        val path = url.substringAfter("://").substringAfter('/')
        val candidates = listOf(url) + postUrls.map { "$it/$path" }
        for (candidate in candidates.distinct()) {
            val res = try {
                app.get(candidate, headers = docHeaders, timeout = 60, allowRedirects = true, interceptor = cfKiller)
            } catch (e: Exception) {
                continue
            }
            if (res.isSuccessful) return FetchedPage(res.text, res.url, res.document)
        }
        throw IllegalArgumentException("post unreachable: $url")
    }

    private fun decodeFileName(b64: String): String = try {
        val decoded = String(Base64.getDecoder().decode(padB64(b64)), Charsets.UTF_8)
        // Filenames are url-safe on some mirrors.
        if (decoded.contains('%')) URLDecoder.decode(decoded, "UTF-8") else decoded
    } catch (e: Exception) {
        ""
    }

    private fun padB64(input: String): ByteArray {
        val padded = input.trim().replace('-', '+').replace('_', '/')
        val rem = padded.length % 4
        val fixed = if (rem == 0) padded else padded + "=".repeat(4 - rem)
        return fixed.toByteArray(Charsets.UTF_8)
    }

    private fun MlbdFileData.toJson(): String = mapper.writeValueAsString(this)

    private fun qualityOf(filename: String): String {
        val m = Regex("(?i)\\b(2160|1440|1080|720|576|480|360|240)p?\\b|\\b4k\\b").find(filename)
            ?: return "HD"
        val token = m.value.lowercase()
        return when {
            token == "4k" -> "2160p"
            token.endsWith("p") -> token
            else -> "${token}p"
        }
    }

    private fun extOf(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return if (ext in setOf("mkv", "mp4", "webm", "avi", "m4v")) ext.uppercase() else "FILE"
    }

    /** "…Episode-01-720p.mkv" / "…S01E02…" / "…E03…" -> 1/2/3 */
    private fun episodeOf(filename: String): Int? {
        Regex("(?i)s\\d+[-_. ]?e(\\d{1,3})").find(filename)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("(?i)episode[-_. ]?(\\d{1,3})").find(filename)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("(?i)\\bep[-_. ]?(\\d{1,3})\\b").find(filename)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    /** "…Episodes0108…" -> (1, 8) batch ranges used by the site for multi-ep files. */
    private fun epRangeOf(filename: String): Pair<Int, Int>? {
        val m = Regex("(?i)episodes?[-_. ]?(\\d{2,3})(\\d{2,3})(?=[^0-9]|$)").find(filename) ?: return null
        var lo = m.groupValues[1].toIntOrNull() ?: return null
        var hi = m.groupValues[2].toIntOrNull() ?: return null
        if (hi < lo) { val t = lo; lo = hi; hi = t }
        return if (lo in 1..999 && hi in lo..999) lo to hi else null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val entries = data.split("|")
            .mapNotNull { tryParseJson<MlbdFileData>(it) }
            .filter { !it.b64.isNullOrBlank() }
        if (entries.isEmpty()) return false

        var found = false
        for (entry in entries) {
            try {
                val text = app.get(signApi + entry.b64, headers = docHeaders + mapOf(
                    "Accept" to "application/json",
                ), timeout = 30, interceptor = cfKiller).text
                val signed = tryParseJson<SignResponse>(text)?.downloadUrl
                    ?.takeIf { it.startsWith("http") } ?: continue
                val quality = qualityOf(entry.name.orEmpty())
                val label = buildString {
                    append(quality)
                    append(" · ")
                    append(extOf(entry.name.orEmpty()))
                }
                callback(newExtractorLink(name, "$name [$label]", signed, ExtractorLinkType.VIDEO) {
                    this.quality = when (quality) {
                        "2160p" -> 2160
                        "1440p" -> 1440
                        "1080p" -> 1080
                        "720p" -> 720
                        "480p" -> 480
                        "360p" -> 360
                        else -> 0
                    }
                    this.referer = "$mainUrl/"
                    this.headers = docHeaders
                })
                found = true
            } catch (e: Exception) {
                // keep trying the remaining files
            }
        }
        return found
    }
}
