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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Krx18 (https://krx18.com) - NSFW Dooplay 2.5.22 based erotic movie site
 *
 * Player chain (v2):
 *  1. Dooplay player API: GET /wp-json/dooplayer/v2/{postid}/{type}/{nume}
 *     -> { type: "iframe", embed_url: "https://play.playkrx18.site/play/{hash}" }
 *     (v1 used the legacy /v2/{postid} route which 404s on Dooplay 2.5.22 -> no links)
 *  2. playkrx18 embeds are "9stream" players: page carries AES-GCM encrypted
 *     idfile_enc / idUser_enc constants.
 *      - decrypt with fixed ASCII key/iv (7bd...c1 / v3M...k1, ...c2 / ...k2)
 *      - POST {data: base64(AES-GCM(json{idfile,iduser,domain_play,unixTimestamp}, ...c3 / ...k3))}
 *        to https://api-play-9str.playkrx18.site/api/getplay
 *      - response { status: "success", type: "url-m3u8-encv1", data }
 *      - decrypt data with 9bd30d612d2bb1bda34743f8608e68c4 / z3M9zQ2pX7k4 -> final HLS URL
 *  3. Fallbacks: admin-ajax doo_player_ajax (same embed_url shape) + page iframe scan,
 *     plus loadExtractor for any embed hosts the app already knows.
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

        // 9stream AES-GCM material (raw ASCII key/iv, exactly as the site's appv1.js uses)
        private const val K_IDFILE = "7bd30d612d2bb1bda34743f8608e68c1"
        private const val IV_IDFILE = "v3M9zQ2pX7k1"
        private const val K_IDUSER = "7bd30d612d2bb1bda34743f8608e68c2"
        private const val IV_IDUSER = "v3M9zQ2pX7k2"
        private const val K_PAYLOAD = "7bd30d612d2bb1bda34743f8608e68c3"
        private const val IV_PAYLOAD = "v3M9zQ2pX7k3"
        private const val K_RESPONSE = "9bd30d612d2bb1bda34743f8608e68c4"
        private const val IV_RESPONSE = "z3M9zQ2pX7k4"
        private const val NINE_STREAM_API = "https://api-play-9str.playkrx18.site/api/getplay"
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

        // postid from body class, fallback to the ajax player options list
        val postid = Regex("postid-(\\d+)")
            .find(doc.body().attr("class") ?: "")?.groupValues?.get(1)
            ?: doc.selectFirst("li.dooplay_player_option[data-post]")?.attr("data-post")

        val data = "$url##${postid ?: ""}"

        return newMovieLoadResponse(title, url, TvType.NSFW, data) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags.ifEmpty { listOf("Krx18") }
            this.year = year
            this.duration = duration
        }
    }

    // ---------------- crypto helpers (AES-GCM, mirrors the site's appv1.js) ----------------
    private fun aesGcmDecryptToText(b64: String, keyStr: String, ivStr: String): String? {
        return try {
            val key = SecretKeySpec(keyStr.toByteArray(Charsets.UTF_8), "AES")
            val spec = GCMParameterSpec(128, ivStr.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val plain = cipher.doFinal(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun aesGcmEncryptToB64(text: String, keyStr: String, ivStr: String): String? {
        return try {
            val key = SecretKeySpec(keyStr.toByteArray(Charsets.UTF_8), "AES")
            val spec = GCMParameterSpec(128, ivStr.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)
            // Java GCM appends the 128-bit tag -> ciphertext||tag, same as WebCrypto
            val ct = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- player DTOs ----------------
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class PlayerResp(
        val status: String? = null,
        val type: String? = null,
        val embed_url: String? = null,
        val data: String? = null,
        val msg: String? = null
    )

    private fun extractIframeSrc(html: String): List<String> {
        val out = mutableListOf<String>()
        Regex("iframe[^>]+src=[\"'\\x60]([^\"'\\x60]+)", RegexOption.IGNORE_CASE).findAll(html).forEach {
            out.add(it.groupValues[1])
        }
        if (out.isEmpty() && html.startsWith("http")) out.add(html.trim())
        return out
    }

    /**
     * Resolve a play.playkrx18.site (9stream) embed page to a final HLS url.
     * Chain: embed page -> decrypt idfile/idUser -> AES-GCM payload -> getplay -> decrypt response.
     */
    private suspend fun resolveNineStream(embedUrl: String): String? {
        return try {
            val embedHtml = app.get(embedUrl, headers = headers, interceptor = cfKiller).text

            val idfileEnc = Regex("idfile_enc\\s*=\\s*[\"']([^\"']+)[\"']").find(embedHtml)?.groupValues?.get(1)
                ?: return null
            val idUserEnc = Regex("idUser_enc\\s*=\\s*[\"']([^\"']+)[\"']").find(embedHtml)?.groupValues?.get(1)
                ?: return null

            val idfile = aesGcmDecryptToText(idfileEnc, K_IDFILE, IV_IDFILE)?.trim() ?: return null
            val iduser = aesGcmDecryptToText(idUserEnc, K_IDUSER, IV_IDUSER)?.trim() ?: return null

            val payload = JSONObject().apply {
                put("idfile", idfile)
                put("iduser", iduser)
                put("domain_play", mainUrl)
                put("unixTimestamp", System.currentTimeMillis() / 1000)
            }.toString()

            val dataB64 = aesGcmEncryptToB64(payload, K_PAYLOAD, IV_PAYLOAD) ?: return null

            val apiHeaders = mapOf(
                "User-Agent" to UA,
                "Content-Type" to "application/x-www-form-urlencoded",
                "Origin" to "https://play.playkrx18.site",
                "Referer" to embedUrl,
                "Accept" to "application/json, text/javascript, */*; q=0.01"
            )
            val respTxt = app.post(
                NINE_STREAM_API,
                data = mapOf("data" to dataB64),
                headers = apiHeaders
            ).text

            val resp = AppUtils.parseJson<PlayerResp>(respTxt) ?: return null
            if (resp.status != "success") return null
            val blob = resp.data ?: return null

            if (resp.type == "url-m3u8-encv1") {
                aesGcmDecryptToText(blob, K_RESPONSE, IV_RESPONSE)?.trim()?.takeIf { it.startsWith("http") }
            } else {
                blob.takeIf { it.startsWith("http") }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Try every known extraction path for one embed url; returns true if a link was emitted. */
    private suspend fun handleEmbed(
        embed: String,
        server: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (embed.isBlank() || embed.contains("youtube") || embed.contains("doubleclick")) return false

        // 1) 9stream self-hosted player (play.playkrx18.site) -> direct HLS
        if (embed.contains("playkrx18.site")) {
            val m3u8 = resolveNineStream(embed)
            if (!m3u8.isNullOrEmpty()) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "$name - $server",
                        url = m3u8
                    ) {
                        this.referer = ""
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.M3U8
                        this.headers = mapOf("User-Agent" to UA)
                    }
                )
                return true
            }
            // fall through to generic handling if the crypto chain failed
        }

        // 2) direct media url
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
            return true
        }

        // 3) registered extractors (abyss.to, streamtape, dood, ...)
        return try {
            loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)
        } catch (e: Exception) {
            false
        }
    }

    /** All embed/direct sources found for a post, from every known Dooplay endpoint */
    private suspend fun fetchEmbedUrls(pageUrl: String, postid: String?): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()

        // 1) dooplayer/v2 REST API - Dooplay 2.5.x route: /{postid}/{type}/{nume}
        if (!postid.isNullOrBlank()) {
            for (nume in 1..4) {
                try {
                    val txt = app.get(
                        "$mainUrl/wp-json/dooplayer/v2/$postid/movie/$nume",
                        headers = xhrHeaders,
                        interceptor = cfKiller
                    ).text.trim()
                    if (txt.isEmpty() || !txt.startsWith("{")) break
                    val resp = AppUtils.parseJson<PlayerResp>(txt) ?: continue
                    val s = resp.embed_url ?: continue
                    if (!s.startsWith("http")) break
                    out.add(s to "Server $nume")
                } catch (e: Exception) {
                    break
                }
            }
        }

        // 2) admin-ajax doo_player_ajax (fallback; same {type, embed_url} response)
        if (out.isEmpty() && !postid.isNullOrBlank()) {
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
                    extractIframeSrc(resp.embed_url ?: resp.data ?: "").forEach { src ->
                        out.add(src to "Server $nume")
                    }
                } catch (e: Exception) {
                }
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

        // 1) Dooplay player APIs -> embed urls
        val embeds = fetchEmbedUrls(pageUrl, postid)
        for ((embed, server) in embeds) {
            try {
                if (handleEmbed(embed, server, subtitleCallback, callback)) found = true
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
                for ((idx, c) in candidates.distinct().withIndex()) {
                    try {
                        if (handleEmbed(c, "Direct ${idx + 1}", subtitleCallback, callback)) found = true
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
            }
        }

        return found
    }
}
