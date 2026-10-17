package com.redowan

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder

/**
 * SouthFreak (southfreak.vip — "SouthFreak.com Hindi Dubbed Movies Google Drive").
 *
 * Site anatomy (probed live, 2026-10):
 *  - WordPress (custom SouthFreakTheme). Post URLs live at the domain root:
 *    https://southfreak.vip/<slug>/, category archives at /category/<slug>/.
 *  - The WP REST API is fully open and carries the whole post body:
 *      /wp-json/wp/v2/posts?categories=<id>&per_page=20&page=N
 *      /wp-json/wp/v2/posts?search=<q>&per_page=20&page=N
 *    No featured images are registered, so posters are the FIRST <img> of the
 *    post content (TMDB / imgvv / gstatic thumbs).
 *  - Post body = poster <p class="poster"><img>, a red metadata block
 *    (IMDb Rating / Genres / Language / Quality / Size / Director / Writers /
 *    Stars / Storyline) and download anchors of the shape
 *      <a href="https://techzed.info/view/<code>">Download in 1080p - 6.97GB</a>
 *    (one button per quality: 4K/1080p/720p/480p, sometimes "(Source)").
 *  - techzed.info/view/<code> is a link protector page listing the real hosts:
 *      gdflix.dev / newN.gdflix.io / gdxfiles.com  -> pages embed direct
 *          pub-*.r2.dev?token= links that serve video/mkv bytes (verified
 *          live: HTTP 200, content-type video/matroska),
 *      newN.multicloudlinks.com  -> drN.multidownload.lol/d/ direct links
 *          (+ player.php ArtPlayer watch source),
 *      hubcloud.ist  -> JS-gated download generator, emitted as fallback,
 *      direct-dl.lol / gdxshare.com / filebee.xyz (login / CF-gated, skipped).
 *  - Season packs ("The Trap 2026 S01 Complete ...") post one file per
 *    quality; single episodes ("... S05E03") are separate posts. Both are
 *    handled: pack posts load as a series with one episode per quality,
 *    single-episode posts load as movies.
 */
class SouthFreakProvider : MainAPI() {
    override var mainUrl = "https://southfreak.vip"
    override var name = "SouthFreak"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)

    /** Link-protector page every download button points at. */
    private val techzedUrl = "https://techzed.info"

    private val docHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept-Language" to "en-US;q=0.9,en;q=0.8",
    )

    // ---------------- main page: every video category on the site ----------------
    override val mainPage = mainPageOf(
        // languages / regions
        "820" to "Hindi Dubbed",
        "819" to "Dual Audio",
        "653" to "Bengali",
        "4" to "Bollywood",
        "3701" to "Hollywood",
        "5" to "Hindi",
        "303" to "Telugu",
        "208" to "Tamil",
        "69" to "Malayalam",
        "6281" to "Kannada",
        "4006" to "Korean Movie & Drama",
        "30400" to "Punjabi",
        "32467" to "Japanese",
        "37670" to "China + Malaysia",
        "33410" to "Thai + Indonesia",
        "36397" to "Spanish",
        "28805" to "Turkish",
        "6164" to "Persian",
        "37933" to "Western",
        "37531" to "Bangla Dubbed",
        "35" to "Bangla Subtitle",
        "1" to "Anime (JPN)",
        // types / collections
        "7077" to "WEB/TV Series",
        "29937" to "IMDb 250",
        "33830" to "Documentary",
        "37146" to "Natok & Telefilm",
        "37975" to "Reality Show",
        "37976" to "Show",
        "38088" to "Wrestling",
        "12223" to "Upcoming",
        // genres
        "3" to "Action",
        "10" to "Drama",
        "8" to "Thriller",
        "9" to "Comedy",
        "6" to "Romantic",
        "7" to "Crime",
        "33" to "Adventure",
        "29372" to "Horror",
        "13" to "Mystery",
        "1887" to "Sci-Fi",
        "33193" to "Fantasy",
        "33192" to "Family",
        "6983" to "Animation",
        "11" to "Biography",
        "32798" to "History",
        "12" to "Sports",
        "36891" to "Musical",
        "36807" to "War",
        "37120" to "Melodrama",
    )

    // ---------------- WP REST models ----------------
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpPost(
        val id: Long? = null,
        val link: String? = null,
        val title: WpTitle? = null,
        val content: WpContent? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpTitle(val rendered: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpContent(val rendered: String? = null)

    /** One download button captured on a post page. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SfFileData(
        val url: String? = null,
        val label: String? = null,
    )

    /** One mirror host URL parsed out of a techzed page. */
    private data class ResolvedHost(
        val url: String,
        val label: String,
        val quality: Int?,
        val referer: String,
    )

    private fun decodeHtml(text: String?): String? = text
        ?.replace("&#038;", "&")
        ?.replace("&amp;", "&")
        ?.replace("&#8211;", "-")
        ?.replace("&#8217;", "'")
        ?.replace("&#8220;", "\"")
        ?.replace("&#8221;", "\"")
        ?.replace("&quot;", "\"")
        ?.replace("<br />", " ")
        ?.trim()

    /** First image of the post body == the poster (no featured media on this site). */
    private fun posterOf(contentHtml: String?): String? =
        contentHtml?.let { Regex("<img[^>]+src=\"(https?://[^\"]+)\"", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }

    private fun seriesHint(text: String): Boolean = listOf(
        " s0", "s01", "s02", "s03", "season", "web series", "webseries",
        "complete", " episode", "e03", "e02", "e01", "natok", "telefilm"
    ).any { text.lowercase().contains(it) }

    private fun toSearchResponse(title: String, url: String, poster: String?): SearchResponse {
        return if (seriesHint(title)) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        }
    }

    private fun qualityInt(text: String): Int = when (qualityLabel(text)) {
        "2160p" -> 2160
        "1440p" -> 1440
        "1080p" -> 1080
        "720p" -> 720
        "480p" -> 480
        "360p" -> 360
        else -> 0
    }

    /** "Download in 1080p (Source) - 6.97GB" / "4K" -> 2160p style label. */
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

    // ---------------- catalog + search over the open REST API ----------------
    private suspend fun wpPosts(apiUrl: String): List<SearchResponse> {
        val text = try {
            app.get(apiUrl, headers = docHeaders, timeout = 30).text
        } catch (e: Exception) {
            return emptyList()
        }
        val posts = tryParseJson<List<WpPost>>(text) ?: return emptyList()
        return posts.mapNotNull { post ->
            val link = post.link ?: return@mapNotNull null
            val title = decodeHtml(post.title?.rendered) ?: return@mapNotNull null
            toSearchResponse(title, link, posterOf(post.content?.rendered))
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/wp-json/wp/v2/posts?categories=${request.data}" +
                "&per_page=20&page=$page"
        val items = wpPosts(url)
        // REST returns fewer rows than per_page only on the last page and a
        // 400 error page maps to an empty list above.
        val hasNext = items.size >= 20
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isEmpty()) return newSearchResponseList(emptyList(), false)
        val url = "$mainUrl/wp-json/wp/v2/posts?search=" + URLEncoder.encode(q, "UTF-8") +
                "&per_page=20&page=$page"
        val results = wpPosts(url).distinctBy { it.url }
        return newSearchResponseList(results, results.size >= 20)
    }

    // ---------------- load: post page ----------------
    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = docHeaders, timeout = 45, allowRedirects = true)
        val doc = res.document

        // <title> = "<Post Title> - SouthFreak | SouthFreak.com ..." — cleanest source.
        val pageTitle = doc.title().substringBefore(" - SouthFreak").trim()
        val title = pageTitle.takeIf { it.isNotBlank() }
            ?: decodeHtml(doc.selectFirst("h1")?.text())
            ?: "Unknown"

        val body = doc.selectFirst("main.page-body") ?: doc.body() ?: doc
        val poster = body.selectFirst("p.poster img")?.attr("src")
            ?: body.selectFirst("img[src^=http]")?.attr("src")

        // Red metadata block: <br>-separated "Key: value" lines inside one <p>
        // (IMDb Rating: 5.6/10 / Genres: ... / Language: ... / Storyline: ...).
        val metaLines = body.select("p")
            .firstOrNull { it.text().contains("IMDb", ignoreCase = true) && it.text().contains(":") }
            ?.html()?.split(Regex("<br\\s*/?>"))
            ?.map { line -> decodeHtml(line.replace(Regex("<[^>]+>"), " "))?.replace(Regex("\\s+"), " ")?.trim() ?: "" }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        fun metaValue(key: String): String? = metaLines
            .firstOrNull { it.startsWith(key, ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val plot = metaValue("Storyline")
        val language = metaValue("Language")
        val genres = metaValue("Genres")?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }
        val imdbRating = metaValue("IMDb Rating")?.substringBefore('/')?.trim()
        val qualityMeta = metaValue("Quality")
        val sizeMeta = metaValue("Size")

        // Download buttons -> techzed codes with their quality labels.
        val files = mutableListOf<Pair<String, String>>() // url -> label
        val seen = mutableSetOf<String>()
        for (a in body.select("a[href*=techzed]")) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (!href.contains("techzed.info/view/") || !seen.add(href)) continue
            val label = decodeHtml(a.text())?.takeIf { it.isNotBlank() } ?: "Download"
            files.add(href to label)
        }
        // Last resort: any techzed link anywhere on the page.
        if (files.isEmpty()) {
            Regex("https://techzed\\.info/view/[A-Za-z0-9]+").findAll(res.text)
                .map { it.value }.distinct()
                .forEach { files.add(it to "Download") }
        }
        if (files.isEmpty()) files.add(url to "Open post page")

        val isSeries = seriesHint(title)

        // IMDb rating / language / quality from the meta block get folded into
        // the description so the info panel shows the site's own data.
        val plotExtras = listOfNotNull(
            imdbRating?.takeIf { it.isNotBlank() && it != "x" }?.let { "IMDb: $it/10" },
            language?.takeIf { it.isNotBlank() }?.let { "Language: $it" },
            qualityMeta?.takeIf { it.isNotBlank() }?.let { "Quality: $it" },
            sizeMeta?.takeIf { it.isNotBlank() && !it.equals("See below", true) }?.let { "Size: $it" },
            plot?.takeIf { it.isNotBlank() }
        )
        val fullPlot = plotExtras.joinToString("\n").ifBlank { null }

        return if (isSeries) {
            // Season packs ship one file per quality -> one episode per quality.
            val episodes = mutableListOf<Episode>()
            files.forEach { (dl, label) ->
                val q = qualityLabel(label) ?: "Full"
                val size = Regex("(?i)[\\d.]+\\s*[GM]B").find(label)?.value ?: ""
                episodes.add(newEpisode(SfFileData(dl, label).toJson()) {
                    name = listOf("Complete", q, size).filter { it.isNotBlank() }.joinToString(" · ")
                    this.episode = 1
                    this.season = 1
                })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = fullPlot
                this.tags = genres?.takeIf { it.isNotEmpty() }
                    ?: listOfNotNull(language?.takeIf { it.isNotBlank() }, "Web Series")
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, packData(files)) {
                this.posterUrl = poster
                this.plot = fullPlot
                this.tags = genres?.takeIf { it.isNotEmpty() }
                    ?: listOfNotNull(language?.takeIf { it.isNotBlank() })
            }
        }
    }

    private fun packData(files: List<Pair<String, String>>): String =
        files.joinToString("|") { SfFileData(it.first, it.second).toJson() }

    private fun SfFileData.toJson(): String =
        com.lagradost.cloudstream3.mapper.writeValueAsString(this)

    // ---------------- links: techzed -> hosts ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val entries = data.split("|")
            .mapNotNull { tryParseJson<SfFileData>(it) }
            .filter { !it.url.isNullOrBlank() }
        if (entries.isEmpty()) return false

        var found = false
        for (entry in entries) {
            val pageUrl = entry.url ?: continue
            val label = entry.label ?: "Download"
            val quality = qualityInt(label)
            val size = Regex("(?i)[\\d.]+\\s*[GM]B").find(label)?.value ?: ""

            val hosts = resolveTechzed(pageUrl)
            if (hosts.isEmpty()) {
                // TechZed unreachable / empty: hand over the protected link itself.
                emitLink(pageUrl, "$name $size".trim(), 0, "$mainUrl/", callback)
                found = true
                continue
            }
            for (host in hosts) {
                emitLink(host.url, host.label, host.quality ?: 0, host.referer, callback)
                found = true
            }
        }
        return found
    }

    private suspend fun emitLink(
        url: String,
        label: String,
        quality: Int,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(newExtractorLink(name, label, url, ExtractorLinkType.VIDEO) {
            this.quality = quality
            this.referer = referer
            this.headers = docHeaders
        })
    }

    /** Fetches a techzed.info/view/<code> page and resolves every mirror host. */
    private suspend fun resolveTechzed(pageUrl: String): List<ResolvedHost> {
        val res = try {
            app.get(pageUrl, headers = docHeaders + mapOf(
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            ), timeout = 45, allowRedirects = true)
        } catch (e: Exception) {
            return emptyList()
        }
        if (!res.isSuccessful) return emptyList()
        val html = res.text

        val mirrors = Regex("href=\"(https?://[^\"]+)\"").findAll(html)
            .map { it.groupValues[1].replace("&amp;", "&") }
            .filter { !it.contains("techzed.info") }
            .distinctBy { it.substringBefore("?") }
            .toList()

        val out = mutableListOf<ResolvedHost>()
        for (mirror in mirrors) {
            when {
                // GDFlix clones embed direct pub-*.r2.dev?token= object links.
                mirror.contains("gdflix.") || mirror.contains("gdxfiles.") ->
                    out.addAll(resolveGdflix(mirror))
                // MultiCloud hands out drN.multidownload.lol/d/ direct links.
                mirror.contains("multicloudlinks.") ->
                    out.addAll(resolveMulticloud(mirror))
                // JS-gated download generator — emit the page itself as fallback.
                mirror.contains("hubcloud.") -> {
                    val name = mirror.substringAfterLast('/')
                    out.add(ResolvedHost(mirror, "HubCloud · $name".take(70), null, mirror))
                }
                // direct-dl.lol (login), gdxshare (login), filebee (CF-gated): skip.
            }
        }
        // Prefer direct R2 object storage, then multidownload.
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

        // <title> carries the real filename: "GDFlix | <name>.mkv"
        val filename = doc.title().substringAfter("|").trim().ifBlank { "download" }
        val qLabel = qualityLabel(filename)
        val quality = qualityInt(filename)

        val links = mutableListOf<ResolvedHost>()
        Regex("https://pub-[A-Za-z0-9-]+\\.r2\\.dev/[^\"'<>\\s]+").findAll(html)
            .map { it.value.replace("&amp;", "&") }
            .distinct()
            .take(2)
            .forEach { links.add(ResolvedHost(it, "${qLabel ?: "HD"} GDFlix R2".trim(), quality, url)) }
        Regex("https://instant\\.busycdn\\.xyz/[A-Za-z0-9]+").findAll(html)
            .map { it.value }.distinct().take(1)
            .forEach { links.add(ResolvedHost(it, "${qLabel ?: "HD"} BusyCDN".trim(), quality, url)) }
        if (links.isEmpty()) {
            val title = filename.substringBeforeLast('.').trim()
            links.add(ResolvedHost(url, "GDFlix · $title".take(70), quality, url))
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

        // Tokenised direct download links on the view page itself.
        Regex("https://dr\\d*\\.multidownload\\.lol/d/[^\"'<>\\s]+").findAll(html)
            .map { it.value.replace("&amp;", "&") }
            .distinct().take(1)
            .forEach { out.add(ResolvedHost(it, "MultiCloud Direct", null, url)) }

        // player.php?v=<code> embeds an ArtPlayer with a streamSrc watch URL.
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
                        src.replace("&amp;", "&"), "MultiCloud Watch", null, player))
                }
            } catch (e: Exception) {
                // keep whatever we already collected
            }
        }
        return out
    }
}
