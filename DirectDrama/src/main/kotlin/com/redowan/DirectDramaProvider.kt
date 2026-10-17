package com.redowan

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DirectDramaProvider : MainAPI() {
    override var mainUrl = "https://directdrama.com"
    override var name = "DirectDrama"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Home",
        "/popular" to "Popular",
        "/new-releases" to "New Releases",
        "/completed" to "Completed Series",
        "/dubbed" to "Dubbed Series",
        "/genre/romance" to "Romance",
        "/genre/mafia" to "Mafia",
        "/genre/billionaire" to "Billionaire",
        "/genre/ceo" to "CEO",
        "/genre/werewolf" to "Werewolf",
        "/genre/revenge" to "Revenge",
        "/genre/historical" to "Historical",
        "/genre/fantasy" to "Fantasy",
        "/genre/sweet" to "Sweet",
        "/genre/strong-female-lead" to "Strong Female Lead",
        "/genre/urban" to "Urban",
        "/platform/reelshort" to "ReelShort",
        "/platform/dramabox" to "DramaBox",
        "/platform/goodshort" to "GoodShort",
        "/platform/shortmax" to "ShortMax",
        "/platform/netshort" to "NetShort",
        "/platform/stardusttv" to "StardustTV",
        "/platform/dramawave" to "DramaWave",
        "/platform/moboreels" to "MoboReels",
    )

    private companion object {
        // The site is a Next.js app: episode ids live inside the RSC
        // payload as \"id\":123,\"number\":1,\"durationSec\"...
        val episodeRegex = Regex("\\\\\"id\\\\\":(\\d+),\\\\\"number\\\\\":(\\d+),\\\\\"durationSec\\\\\"")
        val completedRegex = Regex("\\\\\"status\\\\\":\\\\\"completed\\\\\"")
        val labelEpSuffixRegex = Regex(",\\s*\\d+\\s+episodes?\\s*$", RegexOption.IGNORE_CASE)
        val webpSizeRegex = Regex("/\\d+\\.webp")
        val qualityRegex = Regex("(\\d{3,4})p")

        val docHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Accept-Language" to "en-US,en;q=0.9",
        )
    }

    /** Series card meta found by the search API. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DdSuggestItem(
        val id: Long? = null,
        val slug: String? = null,
        val title: String? = null,
        val coverKey: String? = null,
        val episodes: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DdSuggestResponse(
        val items: List<DdSuggestItem>? = null,
    )

    /** GET /api/stream/<episodeId> response. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DdStreamResponse(
        val episodeId: Long? = null,
        val type: String? = null,
        val url: String? = null,
        val sourceTag: String? = null,
    )

    private fun coverUrl(coverKey: String?): String? =
        coverKey?.takeIf { it.isNotBlank() }?.let { "$mainUrl/img/covers/$it/480.webp" }

    /**
     * The API endpoints require a "viewing context" cookie (dd_view)
     * which the server sets on any document request, so we make a HEAD
     * call first and the cookie jar stores it for the follow-up API call.
     */
    private suspend fun establishViewingContext(path: String) {
        try {
            app.head(fixUrl(path), headers = docHeaders, timeout = 30)
        } catch (e: Exception) {
            // non fatal, some mirrors answer HEAD with 405
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return if (request.data.isBlank()) {
            // Homepage rails: sections with an <h2> followed by series cards
            val doc = app.get("$mainUrl/", headers = docHeaders, timeout = 60).document
            val rows = doc.select("section").mapNotNull { section ->
                val h2 = section.selectFirst("h2") ?: return@mapNotNull null
                h2.select("span.sr-only").remove()
                val heading = h2.text().trim()
                if (heading.isBlank()) return@mapNotNull null
                val cards = section.select("article")
                    .mapNotNull { toResult(it) }
                    .distinctBy { it.url }
                if (cards.isEmpty()) null else HomePageList(heading, cards, isHorizontalImages = false)
            }
            newHomePageResponse(rows, hasNext = false)
        } else {
            // Listing pages paginate as /<path>/<page> (page 1 has no suffix)
            val url = if (page <= 1) "$mainUrl${request.data}" else "$mainUrl${request.data}/$page"
            val doc = app.get(url, headers = docHeaders, timeout = 60, allowRedirects = true).document
            val home = doc.select("article")
                .mapNotNull { toResult(it) }
                .distinctBy { it.url }
            newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
        }
    }

    private fun toResult(post: Element): SearchResponse? {
        val link = post.selectFirst("a[href*=/series/]") ?: return null
        val href = link.attr("href").orEmpty()
        if (href.isBlank() || Regex("/episode-\\d+/?$").containsMatchIn(href)) return null
        val url = fixUrl(href)
        val label = link.attr("aria-label").orEmpty().ifBlank {
            link.selectFirst("img")?.attr("alt").orEmpty()
        }
        val title = label.replace(labelEpSuffixRegex, "").trim()
        if (title.isBlank()) return null
        val img = post.selectFirst("img")?.attr("srcset")?.substringBefore(" ")
            ?: post.selectFirst("img")?.attr("src").orEmpty()
        val poster = img.ifBlank { null }
            ?.let { webpSizeRegex.replace(it, "/480.webp") }
            ?.let { fixUrl(it) }
        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        establishViewingContext("/search")

        val apiUrl = "$mainUrl/api/series/suggest?locale=en&q=" +
                URLEncoder.encode(q, "UTF-8")
        val text = try {
            app.get(apiUrl, headers = docHeaders + mapOf(
                "Accept" to "application/json",
                "Referer" to "$mainUrl/search",
            ), timeout = 30).text
        } catch (e: Exception) {
            return emptyList()
        }

        val parsed = tryParseJson<DdSuggestResponse>(text) ?: return emptyList()
        return parsed.items.orEmpty().mapNotNull { item ->
            val slug = item.slug ?: return@mapNotNull null
            val title = item.title ?: slug
            newTvSeriesSearchResponse(title, "$mainUrl/series/$slug", TvType.TvSeries) {
                this.posterUrl = coverUrl(item.coverKey)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = docHeaders, timeout = 60, allowRedirects = true)
        val doc = res.document
        val html = res.text

        // Metadata from the TVSeries JSON-LD block
        var title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        var poster: String? = null
        var plot: String? = null
        var tags: List<String>? = null
        var numberOfEpisodes: Int? = null
        var completed = false

        for (script in doc.select("script[type=application/ld+json]")) {
            val node = try {
                mapper.readValue(script.data(), JsonNode::class.java)
            } catch (e: Exception) {
                continue
            }
            val graph = node.get("@graph")?.takeIf { it.isArray } ?: continue
            val series = graph.firstOrNull { it.get("@type")?.asText("") == "TVSeries" }
                ?: continue

            series.get("name")?.asText("")?.trim()
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            series.get("description")?.asText("")
                ?.takeIf { it.isNotBlank() }?.let { plot = it }

            val img = series.get("image")
            when {
                img == null -> {}
                img.isArray -> img.firstOrNull()?.get("url")?.asText("")
                    ?.takeIf { it.isNotBlank() }?.let { poster = it }
                img.isTextual -> poster = img.asText("")
            }

            val genre = series.get("genre")
            when {
                genre == null -> {}
                genre.isArray -> tags = genre.map { it.asText("") }
                else -> tags = listOf(genre.asText(""))
            }
            series.get("numberOfEpisodes")?.takeIf { it.isInt }?.let { numberOfEpisodes = it.asInt() }
            break
        }

        // Episode ids live in the Next.js RSC payload of the episode pages.
        // Try the series page first, then fall back to fetching episode 1.
        var firstEpisodeHtml: String? = null
        val epPairs = findEpisodePairs(html)
            .takeIf { it.isNotEmpty() }
            ?: run {
                try {
                    val epRes = app.get("$url/episode-1", headers = docHeaders, timeout = 60, allowRedirects = true)
                    firstEpisodeHtml = epRes.text
                    findEpisodePairs(epRes.text)
                } catch (e: Exception) {
                    emptyList()
                }
            }

        val episodes: List<Episode> = if (epPairs.isNotEmpty()) {
            epPairs.map { (number, id) ->
                newEpisode(DdEpisodeData(id = id, href = "$url/episode-$number", number = number)) {
                    this.name = "Episode $number"
                    this.episode = number
                }
            }
        } else {
            // Last resort: no ids available yet, loadLinks will resolve them
            val count = numberOfEpisodes ?: 0
            (1..count).map { n ->
                newEpisode(DdEpisodeData(id = null, href = "$url/episode-$n", number = n)) {
                    this.name = "Episode $n"
                    this.episode = n
                }
            }
        }

        // "status":"completed" appears in the RSC payload for finished shows
        val isCompleted = completedRegex.containsMatchIn(html) ||
                firstEpisodeHtml?.let { completedRegex.containsMatchIn(it) } == true
        if (isCompleted) {
            completed = true
        }

        return newTvSeriesLoadResponse(
            title.ifBlank { url },
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.showStatus = if (completed) ShowStatus.Completed else ShowStatus.Ongoing
        }
    }

    /** Finds (number, id) pairs inside the RSC episodes array, if present. */
    private fun findEpisodePairs(html: String): List<Pair<Int, String>> {
        val seriesMarker = "\\\"series\\\":{"
        val start = html.indexOf(seriesMarker)
        val searchFrom = if (start == -1) 0 else start
        val arrayMarker = "\\\"episodes\\\":["
        val arrayStart = html.indexOf(arrayMarker, searchFrom)
        if (arrayStart == -1) return emptyList()
        val end = html.indexOf(']', arrayStart)
        if (end == -1) return emptyList()
        val region = html.substring(arrayStart, end + 1)
        return episodeRegex.findAll(region)
            .mapNotNull { m ->
                val number = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                number to m.groupValues[1]
            }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .toList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val epData = tryParseJson<DdEpisodeData>(data)
        val episodeHref = epData?.href?.takeIf { it.isNotBlank() } ?: return false
        var episodeId = epData.id?.takeIf { it.isNotBlank() }

        // Resolve missing episode ids by fetching the episode page. This
        // doubles as establishing the viewing context (dd_view cookie).
        if (episodeId == null) {
            episodeId = try {
                val epHtml = app.get(episodeHref, headers = docHeaders, timeout = 60, allowRedirects = true).text
                findEpisodePairs(epHtml)
                    .firstOrNull { epData.number == null || it.first == epData.number }
                    ?.second
            } catch (e: Exception) {
                null
            } ?: return false
        } else {
            // The stream API 403s with "viewing_context_required" unless a
            // document request has been made first (sets the dd_view cookie).
            establishViewingContext(episodeHref.substringAfter(mainUrl, "/"))
        }

        val apiUrl = "$mainUrl/api/stream/$episodeId?locale=en"
        val text = try {
            app.get(apiUrl, headers = docHeaders + mapOf(
                "Accept" to "application/json",
                "Referer" to episodeHref,
            ), timeout = 30).text
        } catch (e: Exception) {
            return false
        }

        val stream = tryParseJson<DdStreamResponse>(text) ?: return false
        val linkUrl = stream.url?.takeIf { it.isNotBlank() } ?: return false
        val quality = qualityRegex.find(linkUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 540

        callback(newExtractorLink(source = name, name = name, url = linkUrl) {
            this.referer = "$mainUrl/"
            this.quality = quality
            type = ExtractorLinkType.VIDEO
        })
        return true
    }
}

/** Data passed from load() to loadLinks() for each episode. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DdEpisodeData(
    val id: String? = null,
    val href: String? = null,
    val number: Int? = null,
)


