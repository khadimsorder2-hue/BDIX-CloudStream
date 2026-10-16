package com.redowan

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Krx18 (https://krx18.com) - NSFW Dooplay-based erotic movie site
 *
 * Features:
 *  - Latest movies + ALL genres (countries & tags)
 *  - Paginated search (with Cloudflare handling via CloudflareKiller)
 *  - Player extraction via Dooplay admin-ajax (doo_player_ajax) + dooplayer/v2 REST,
 *    with embed host auto-handling through loadExtractor and direct-link fallback
 */
open class Krx18Provider : MainAPI() {
    override var mainUrl = "https://krx18.com"
    override var name = "Krx18"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override var lang = "bn"
    override val supportedTypes = setOf(
        TvType.NSFW,
        TvType.Movie
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val cfKiller = CloudflareKiller()

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    private val xhrHeaders = mapOf(
        "User-Agent" to UA,
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Referer" to "$mainUrl/"
    )

    // ---------------- ALL genres ----------------
    override val mainPage = mainPageOf(
        "/movies/" to "Latest",
        // --- special / clip ---
        "/genre/xxx/" to "X Clip",
        "/genre/eng-sub/" to "Eng Sub",
        // --- countries ---
        "/genre/korea/" to "Korea",
        "/genre/japan/" to "Japan",
        "/genre/india/" to "India",
        "/genre/philippines/" to "Philippines",
        "/genre/thailand/" to "Thailand",
        "/genre/china/" to "China",
        "/genre/taiwan/" to "Taiwan",
        "/genre/indonesia/" to "Indonesia",
        "/genre/asian/" to "Asian",
        "/genre/usa/" to "USA",
        "/genre/uk/" to "UK",
        "/genre/australia/" to "Australia",
        "/genre/canada/" to "Canada",
        "/genre/france/" to "France",
        "/genre/germany/" to "Germany",
        "/genre/italy/" to "Italy",
        "/genre/spain/" to "Spain",
        "/genre/russia/" to "Russia",
        "/genre/poland/" to "Poland",
        "/genre/netherlands/" to "Netherlands",
        "/genre/belgium/" to "Belgium",
        "/genre/denmark/" to "Denmark",
        "/genre/switzerland/" to "Switzerland",
        "/genre/singapore/" to "Singapore",
        "/genre/latina/" to "Latina",
        // --- tags ---
        "/genre/sex/" to "Sex",
        "/genre/anal/" to "Anal",
        "/genre/big-boobs/" to "Big Boobs",
        "/genre/blowjob/" to "Blowjob",
        "/genre/masturbation/" to "Masturbation",
        "/genre/bbc/" to "BBC",
        "/genre/bwc/" to "BWC",
        "/genre/teen/" to "Teen",
        "/genre/petite/" to "Petite",
        "/genre/feet/" to "Feet",
        "/genre/fetish/" to "Fetish",
        "/genre/threesome/" to "Threesome",
        "/genre/striptease/" to "Striptease",
        "/genre/exclusive/" to "Exclusive"
    )

    private fun listUrl(data: String, page: Int): String =
        if (page <= 1) "$mainUrl$data" else "$mainUrl${data}page/$page/"

    // ---------------- HOME ----------------
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = listUrl(request.data, page)
        val doc = app.get(url, headers = headers, interceptor = cfKiller).document
        val items = doc.select("article.item").mapNotNull { toResult(it) }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    // ---------------- card mapper ----------------
    private fun cleanImg(src: String?): String? {
        if (src.isNullOrBlank()) return null
        if (src.contains("px.gif") || src.startsWith("data:")) return null
        return src
    }

    private fun toResult(post: Element): SearchResponse? {
        val a = post.selectFirst("h3 a[href]") ?: post.selectFirst("a[href*=\"/movies/\"]")
        ?: return null
        val url = a.attr("href")
        if (url.isBlank()) return null

        val title = a.attr("title").takeIf { it.isNotBlank() }
            ?: a.text().takeIf { it.isNotBlank() }
            ?: post.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null

        val img = post.selectFirst(".poster img") ?: post.selectFirst("img")
        val poster = cleanImg(img?.attr("src"))
            ?: cleanImg(img?.attr("data-src"))
            ?: cleanImg(img?.attr("data-litespeed-src"))

        val year = post.selectFirst(".metadata span")?.text()?.trim()?.toIntOrNull()
            ?: Regex("(19|20)\\d{2}").find(post.selectFirst(".data span")?.text() ?: "")?.value?.toIntOrNull()

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.year = year
        }
    }

    // ---------------- SEARCH ----------------
    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        val enc = URLEncoder.encode(query, "UTF-8")

        val candidates = if (page <= 1) {
            listOf("$mainUrl/?s=$enc", "$mainUrl/search/${URLEncoder.encode(query, "UTF-8").replace("+", "-")}/")
        } else {
            listOf("$mainUrl/page/$page/?s=$enc", "$mainUrl/?s=$enc&paged=$page")
        }

        for (u in candidates) {
            try {
                val res = app.get(u, headers = headers, interceptor = cfKiller)
                if (!res.text.contains("<article")) continue
                val doc = res.document
                val items = doc.select("article.item").mapNotNull { toResult(it) }
                if (items.isEmpty()) continue
                val hasNext = doc.selectFirst("a[rel=next], .pagination .arrow i + a, .pagination a") != null ||
                    items.size >= 30
                return newSearchResponseList(items, hasNext)
            } catch (e: Exception) {
                // try next candidate
            }
        }
        return newSearchResponseList(emptyList(), false)
    }

    // ---------------- LOAD ----------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, interceptor = cfKiller).document

        val title = doc.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?.replace(Regex("\\s*\\|.*$"), "")
            ?.trim()
            ?: doc.selectFirst("h1")?.text()
            ?: "Krx18 Movie"

        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?: cleanImg(doc.selectFirst(".poster img, article img")?.attr("src"))

        val plot = doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")
            ?: doc.selectFirst(".wp-content p, .description p")?.text()

        val meta = doc.select(".metadata span").map { it.text().trim() }
        val year = meta.firstOrNull { Regex("^(19|20)\\d{2}$").matches(it) }?.toIntOrNull()
        val duration = meta.firstOrNull { it.contains("min") }
            ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val tags = doc.select("nav.genres a, .genres a, .sgeneros a").map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("Genre:", true) }
            .distinct()

        val rating = doc.selectFirst(".rating, .dt_rating_vgs")?.text()
            ?.trim()?.takeIf { it.isNotBlank() }

        val postid = Regex("postid-(\\d+)")
            .find(doc.body().attr("class") ?: "")?.groupValues?.get(1)

        val data = "$url##${postid ?: ""}"

        return newMovieLoadResponse(title, url, TvType.NSFW, data) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags.ifEmpty { listOf("Krx18") }
            this.year = year
            this.duration = duration
        }
    }

    // ---------------- player DTOs ----------------
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class PlayerResp(
        val type: String? = null,
        val link: String? = null,
        val name: String? = null,
        val source: String? = null,
        val title: String? = null,
        val embed_url: String? = null
    )

    private fun extractIframeSrc(html: String): List<String> {
        val out = mutableListOf<String>()
        Regex("iframe[^>]+src=[\"'\\x60]([^\"'\\x60]+)", RegexOption.IGNORE_CASE).findAll(html).forEach {
            out.add(it.groupValues[1])
        }
        if (out.isEmpty() && html.startsWith("http")) out.add(html.trim())
        return out
    }

    /** All embed/direct sources found for a post, from every known Dooplay endpoint */
    private suspend fun fetchEmbedUrls(pageUrl: String, postid: String?): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (postid.isNullOrBlank()) return out

        // 1) dooplayer/v2 REST API (all servers)
        try {
            val txt = app.get(
                "$mainUrl/wp-json/dooplayer/v2/$postid",
                headers = xhrHeaders,
                interceptor = cfKiller
            ).text
            if (txt.trimStart().startsWith("[")) {
                AppUtils.parseJson<List<PlayerResp>>(txt).orEmpty().forEach {
                    val s = it.source ?: it.link ?: it.embed_url
                    if (!s.isNullOrBlank()) out.add(s to (it.name ?: "Server"))
                }
            } else if (txt.trimStart().startsWith("{")) {
                AppUtils.parseJson<PlayerResp>(txt)?.let {
                    val s = it.source ?: it.link ?: it.embed_url
                    if (!s.isNullOrBlank()) out.add(s to (it.name ?: "Server"))
                }
            }
        } catch (e: Exception) {
        }

        // 2) admin-ajax doo_player_ajax (servers 1..3)
        for (nume in 1..3) {
            try {
                val txt = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to postid,
                        "nume" to "$nume",
                        "type" to "movie"
                    ),
                    headers = xhrHeaders,
                    interceptor = cfKiller
                ).text
                if (txt.isBlank() || txt.contains("error code:") || txt.contains("Just a moment")) continue
                val resp = AppUtils.parseJson<PlayerResp>(txt) ?: continue
                extractIframeSrc(resp.link ?: "").forEach { src ->
                    out.add(src to (resp.title ?: "Server $nume"))
                }
            } catch (e: Exception) {
            }
        }

        return out.distinctBy { it.first }
    }

    // ---------------- LINKS ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val parts = data.split("##")
        val pageUrl = parts.getOrNull(0)?.trim().orEmpty()
        val postid = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() }

        var found = false

        // 1) Dooplay player APIs
        val embeds = fetchEmbedUrls(pageUrl, postid)
        for ((embed, server) in embeds) {
            try {
                val handled = loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)
                if (!handled) {
                    val isDirect = Regex("\\.(mp4|m3u8|mkv)(\\?|$)", RegexOption.IGNORE_CASE)
                        .containsMatchIn(embed)
                    if (isDirect) {
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "$name - $server",
                                url = embed
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")
                            }
                        )
                    } else {
                        continue
                    }
                }
                found = true
            } catch (e: Exception) {
            }
        }

        // 2) fallback: scan the page itself for iframes / direct media
        if (!found && pageUrl.isNotBlank()) {
            try {
                val doc = app.get(pageUrl, headers = headers, interceptor = cfKiller).document
                val candidates = mutableListOf<String>()
                doc.select("iframe[src]").forEach { candidates.add(it.attr("src")) }
                doc.select("[data-litespeed-src]").forEach { candidates.add(it.attr("data-litespeed-src")) }
                Regex("https?://[^\"'\\s]+\\.(?:mp4|m3u8)[^\"'\\s]*").findAll(doc.html()).forEach {
                    candidates.add(it.value)
                }
                for (c in candidates.distinct()) {
                    if (c.contains("youtube") || c.contains("doubleclick") || c.contains("adservice")) continue
                    try {
                        if (!loadExtractor(c, "$mainUrl/", subtitleCallback, callback)) {
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = "$name - Direct",
                                    url = c
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")
                                }
                            )
                        }
                        found = true
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
            }
        }

        return found
    }
}
