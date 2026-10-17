package com.redowan

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class StreamingVerticalsProvider : MainAPI() {
    override var mainUrl = "https://www.streamingverticals.com"
    override var name = "StreamingVerticals"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "Home",
        "/hottest" to "Hottest Right Now",
        "/popular" to "Most Popular",
        "/new" to "New Dramas",
        "/live-action" to "Live Action",
        "/ai-generated" to "AI Generated",
        "/movies" to "Full Movies",
        "/wide-screen" to "Wide-Screen Dramas",
        "/chinese-dramas" to "Chinese Dramas",
        "/genre/billionaire" to "Billionaire & CEO",
        "/genre/werewolf" to "Werewolf & Alpha",
        "/genre/mafia" to "Mafia Dramas",
        "/genre/revenge" to "Revenge Dramas",
        "/genre/forbidden" to "Forbidden Love",
        "/genre/contract-marriage" to "Contract Marriage",
        "/genre/hidden-identity" to "Hidden Identity",
        "/genre/fantasy" to "Fantasy Dramas",
        "/genre/vampire" to "Vampire Dramas",
        "/genre/romance" to "Romance Dramas",
        "/genre/campus" to "Campus Dramas",
        "/genre/action" to "Action Dramas",
        "/genre/comedy" to "Comedy Dramas",
        "/studio/reelshort" to "ReelShort",
        "/studio/dramabox" to "DramaBox",
        "/studio/goodshort" to "GoodShort",
        "/studio/shortmax" to "ShortMax",
        "/studio/netshort" to "NetShort",
        "/studio/stardusttv" to "StardustTV",
    )

    private companion object {
        @Volatile
        var cachedIndex: List<SvIndexItem>? = null

        val embedIdRegex = Regex("embed/([\\w-]+)")
        val thumbIdRegex = Regex("/vi/([\\w-]+)/")
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SvIndexItem(
        val s: String? = null, // slug
        val t: String? = null, // title
        val c: String? = null, // creator / studio
        val i: String? = null, // thumbnail url
        val y: String? = null, // youtube video id
        val v: Long = 0,       // views
        val d: String? = null, // description
    )

    private suspend fun getIndex(): List<SvIndexItem> {
        cachedIndex?.let { return it }
        // No lock: worst case two concurrent searches both download the index once.
        return try {
            val text = app.get("$mainUrl/search-index.json", timeout = 120).text
            val items = tryParseJson<List<SvIndexItem>>(text).orEmpty()
            if (items.isNotEmpty()) cachedIndex = items
            items
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return if (request.data.isBlank()) {
            // Homepage has multiple rails: <section class="row" data-row="TITLE">
            val doc = app.get("$mainUrl/", timeout = 60).document
            val rows = doc.select("section.row").mapNotNull { section ->
                val rowName = section.attr("data-row").ifBlank { return@mapNotNull null }
                val cards = section.select("a.card").mapNotNull { toResult(it) }
                if (cards.isEmpty()) null else HomePageList(rowName, cards, isHorizontalImages = false)
            }
            newHomePageResponse(rows, hasNext = false)
        } else {
            // Listing pages paginate as /page/N (page 1 has no suffix)
            val url = if (page <= 1) "$mainUrl${request.data}" else "$mainUrl${request.data}/page/$page"
            val doc = app.get(url, timeout = 60, allowRedirects = true).document
            val home = doc.select("a.card").mapNotNull { toResult(it) }
            newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
        }
    }

    private fun toResult(post: Element): SearchResponse? {
        val href = post.attr("href").orEmpty()
        if (href.isBlank() || !href.contains("/watch/")) return null
        val url = fixUrl(href)
        val title = post.selectFirst(".card-title")?.text()
            ?: post.attr("data-slug").ifBlank { null }
            ?: return null
        val img = post.selectFirst("img")?.attr("src").orEmpty()
        val poster = thumbIdRegex.find(img)?.let { "https://i.ytimg.com/vi/${it.groupValues[1]}/hqdefault.jpg" }
            ?: img.ifBlank { null }
        return newMovieSearchResponse(title.trim(), url, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val words = q.split(" ").filter { it.isNotBlank() }
        val index = getIndex()
        if (index.isEmpty()) return emptyList()

        // The site itself searches client-side over search-index.json,
        // so we do the same: match every word against the title.
        val results = index.asSequence()
            .filter { item ->
                val title = item.t?.lowercase() ?: return@filter false
                words.all { title.contains(it) }
            }
            .sortedByDescending { it.v }
            .take(40)
            .toList()

        return results.map { item ->
            newMovieSearchResponse(
                item.t ?: item.s.orEmpty(),
                "$mainUrl/watch/${item.s}",
                TvType.Movie
            ) {
                this.posterUrl = item.i
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 60, allowRedirects = true).document
        val player = doc.selectFirst(".player")
        val title = (player?.attr("data-title")?.ifBlank { null }
            ?: doc.selectFirst("h1")?.text()
            ?: "Unknown").replace(Regex("\\s+Episodes?.*$", RegexOption.IGNORE_CASE), "").trim()

        val embed = player?.attr("data-embed").orEmpty()
        val videoData = embedIdRegex.find(embed)?.groupValues?.getOrNull(1)
            ?: embedIdRegex.find(doc.selectFirst("meta[property=og:video:url]")?.attr("content").orEmpty())?.groupValues?.getOrNull(1)
            ?: embed

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst(".player img")?.attr("src")

        val plot = doc.selectFirst(".watch-info p.desc")?.text()?.ifBlank { null }
            ?: doc.selectFirst("p.desc")?.text()?.ifBlank { null }

        val tags = player?.attr("data-genres")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }

        val creator = player?.attr("data-creator")?.ifBlank { null }
        val desc = listOfNotNull(
            plot,
            creator?.let { "Studio: $it" }
        ).joinToString("\n\n")

        return newMovieLoadResponse(title, url, TvType.Movie, videoData) {
            this.posterUrl = poster
            this.plot = desc.ifBlank { null }
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        // Data is a YouTube id or a full embed url; the app's built-in
        // YoutubeExtractor handles youtube.com/youtu.be links.
        val link = if (data.startsWith("http")) data else "https://www.youtube.com/watch?v=$data"
        return loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
    }
}
