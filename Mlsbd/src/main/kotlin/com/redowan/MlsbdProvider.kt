package com.redowan

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * MLSBD (mlsbd.co — "The Largest Movie Link Store of Bangladesh").
 *
 * Architecture mirrors the battle-tested CNCVerse MlsbdProvider:
 *  - Mobile Chrome UA + optional `cf_clearance` cookie on every request.
 *  - Cloudflare pages are detected, then solved inside a WebView dialog the
 *    user can interact with once; the clearance cookie is persisted and every
 *    later request reuses it together with the WebView's own User-Agent.
 *  - Catalog cards: div.single-post (title h2/.post-title, poster data-src/src).
 *  - Post pages: `div.post-section-title` sections ("Download Now Epi-55")
 *    each holding `a.Dbtn` buttons that point at savelinks.me/view/<code>.
 *  - savelinks view pages list the real file hosts (gdflix / gdxfiles /
 *    filepress / multicloudlinks / hubcloud / pixeldrain / gofile / filesdl),
 *    each resolved by a dedicated ExtractorApi in Extractors.kt.
 */
class MlsbdProvider : MainAPI() {

    override var mainUrl = "https://mlsbd.co"
    override var name = "MLSBD"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.AnimeMovie)

    companion object {
        @Volatile
        var appContext: Context? = null

        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val SAVELINKS_URL = "https://savelinks.me"
    }

    /** Serialises WebView challenge solving so parallel home rows don't spawn dialogs. */
    private val cfMutex = Mutex()

    @Volatile
    private var webViewUa: String? = null

    private val baseHeaders = mapOf(
        "User-Agent" to MOBILE_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "bn,en-US;q=0.9,en;q=0.8",
        "Upgrade-Insecure-Requests" to "1",
    )

    /** Latest on the homepage, then the four priority sections first. */
    override val mainPage = mainPageOf(
        "" to "Latest Movies",
        "category/bangla-movies" to "Bengali Movies",
        "category/bangla-dubbed" to "Bangla Dubbed",
        "category/bollywood-movies" to "Bollywood Movies",
        "category/dual-audio-movies" to "Multi Audio Movies",
        "category/hollywood-movies" to "Hollywood Movies",
        "category/tv-series" to "TV Series",
    )

    // ------------------------------------------------------------------ fetch

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

    /** One plain attempt with an optional cf_clearance cookie.
     *  After a WebView solve the WebView's own UA must be reused —
     *  cf_clearance is bound to the User-Agent that earned it. */
    private fun fetchHeaders(cf: String?): Map<String, String> {
        var h = baseHeaders
        val ua = webViewUa
        if (!ua.isNullOrBlank()) h = h + ("User-Agent" to ua)
        if (!cf.isNullOrBlank()) h = h + ("Cookie" to "cf_clearance=$cf")
        return h
    }

    private suspend fun tryDoc(url: String, cf: String?): Document? {
        return try {
            val res = app.get(url, headers = fetchHeaders(cf), timeout = 60)
            if (res.isSuccessful && !isCloudflare(res.text, res.code)) res.document else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchHtml(url: String): Document {
        val (savedCf, _) = MlsbdStorage.getCfCookie()
        tryDoc(url, savedCf)?.let { return it }

        cfMutex.withLock {
            // Another row may have solved the challenge while we waited.
            val (cfNow, _) = MlsbdStorage.getCfCookie()
            if (!cfNow.isNullOrBlank() && cfNow != savedCf) {
                tryDoc(url, cfNow)?.let { return it }
            }
            val html = fetchViaWebView(url)
            return when {
                html != null && html.length > 500 -> Jsoup.parse(html, url)
                else -> tryDoc(url, null) ?: Jsoup.parse("", url)
            }
        }
    }

    /**
     * Loads [url] in a visible WebView dialog so a Cloudflare/Turnstile
     * challenge can be solved once; polls the rendered DOM and returns the
     * final HTML. The WebView's UA is captured — cf_clearance is UA-bound.
     */
    private suspend fun fetchViaWebView(url: String): String? {
        val ctx = appContext ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var resolved = false
                var dialog: AlertDialog? = null
                var webView: WebView? = null
                val handler = Handler(Looper.getMainLooper())

                fun finish(html: String?) {
                    if (resolved) return
                    resolved = true
                    try { dialog?.dismiss() } catch (e: Exception) {}
                    try { webView?.destroy() } catch (e: Exception) {}
                    if (cont.isActive) cont.resume(html)
                }

                try {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)

                    val wv = WebView(ctx)
                    webView = wv
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    webViewUa = wv.settings.userAgentString
                    wv.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )

                    val container = FrameLayout(ctx)
                    container.addView(wv)

                    val wrapper = LinearLayout(ctx)
                    wrapper.orientation = LinearLayout.VERTICAL
                    val tip = TextView(ctx)
                    tip.text = "MLSBD: if a security check appears, tap \"Verify you are human\" once."
                    tip.setTextColor(Color.WHITE)
                    tip.setBackgroundColor(Color.parseColor("#0d1117"))
                    val pad = (10 * ctx.resources.displayMetrics.density).toInt()
                    tip.setPadding(pad, pad, pad, pad)
                    wrapper.addView(tip)
                    wrapper.addView(container)

                    val dlg = AlertDialog.Builder(ctx)
                        .setView(wrapper)
                        .setCancelable(true)
                        .setOnDismissListener { finish(null) }
                        .create()
                    dialog = dlg
                    dlg.show()
                    dlg.window?.setLayout(
                        (ctx.resources.displayMetrics.widthPixels * 0.95f).toInt(),
                        (ctx.resources.displayMetrics.heightPixels * 0.8f).toInt()
                    )

                    fun poll() {
                        if (resolved) return
                        wv.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                            if (resolved) return@evaluateJavascript
                            val html = unescapeJsString(raw ?: "")
                            val title = wv.title ?: ""
                            val stillCf = title.contains("Just a moment", true) ||
                                title.contains("Cloudflare", true) ||
                                html.contains("Just a moment", true)
                            if (!stillCf && html.length > 3000) {
                                val cookies = cookieManager.getCookie(url) ?: ""
                                Regex("cf_clearance=([^;]+)").find(cookies)?.groupValues?.get(1)
                                    ?.let { MlsbdStorage.saveCfCookie(it) }
                                finish(html)
                            } else {
                                handler.postDelayed({ poll() }, 1000L)
                            }
                        }
                    }

                    wv.webViewClient = WebViewClient()
                    wv.loadUrl(url)
                    handler.postDelayed({ poll() }, 1500L)
                    handler.postDelayed({ finish(null) }, 180_000L)
                } catch (e: Exception) {
                    finish(null)
                }
            }
        }
    }

    /** evaluateJavascript wraps the DOM in a JSON string literal. */
    private fun unescapeJsString(raw: String): String {
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length > 1) {
            return try {
                JSONTokener(raw).nextValue().toString()
            } catch (e: Exception) {
                raw.removeSurrounding("\"")
            }
        }
        return raw
    }

    // ------------------------------------------------------------------ cards

    private fun parseCards(doc: Document): List<SearchResponse> {
        return doc.select("div.single-post, article, .post-item, .item").mapNotNull { card ->
            val titleEl = card.selectFirst("h2, .post-title, .entry-title") ?: return@mapNotNull null
            val title = titleEl.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val a = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.isBlank() || href.contains("/category/") || href.contains("/author/")) {
                return@mapNotNull null
            }
            val poster = card.selectFirst("picture img, img")?.let { img ->
                img.attr("data-src").ifBlank {
                    img.attr("src").ifBlank {
                        img.attr("srcset").substringBefore(" ").trim()
                    }
                }.ifBlank { null }
            }
            if (isSeries(href, title)) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }.distinctBy { it.url }
    }

    private fun isSeries(url: String, title: String): Boolean {
        return url.contains("/tv-series/") ||
            Regex("(?i)\\bs\\d{1,2}(?:e\\d{1,3})?\\b|season\\s*\\d|web.?series|\\bepisode\\b")
                .containsMatchIn(title)
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
        val doc = fetchHtml(url)
        val items = parseCards(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val doc = fetchHtml("$mainUrl/?s=" + URLEncoder.encode(q, "UTF-8"))
        return parseCards(doc)
    }

    // ------------------------------------------------------------------ load

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SavelinkEntry(
        val code: String? = null,
        val label: String? = null,
        val quality: Int = 0,
    )

    private fun SavelinkEntry.toJson(): String = mapper.writeValueAsString(this)

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchHtml(url)

        val title = doc.selectFirst("h1.entry-title, h1, .name")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: url
        val poster = doc.selectFirst("img.aligncenter, .entry-content img, picture img")?.attr("src")?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = doc.select("a[rel=tag]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct().ifEmpty { null }
        val plot = doc.selectFirst(".post-content p, .entry-content p")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val year = Regex("(?<=\\()\\d{4}(?=\\))").find(title)?.value?.toIntOrNull()
            ?: Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()

        // Walk the download sections in document order; each section is one
        // episode (series) or one quality group (movies).
        val sections = LinkedHashMap<String, MutableList<SavelinkEntry>>()
        var current = "Download Now"
        for (el in doc.select("div.post-section-title, a.Dbtn, a.sd, a.hd, a.hevc")) {
            if (el.tagName() == "div") {
                current = el.text().trim().ifBlank { "Download Now" }
                sections.getOrPut(current) { mutableListOf() }
                continue
            }
            val href = el.attr("abs:href").ifBlank { el.attr("href") }
            if (href.isBlank()) continue
            val code = Regex("savelinks\\.me/view/([A-Za-z0-9]+)").find(href)?.groupValues?.get(1)
                ?: continue
            val cls = el.className().lowercase()
            val label = el.text().replace(Regex("\\s+"), " ").trim().ifBlank { "Download" }
            val q = mlsbdQualityNum("$label $cls")
            val bucket = sections.getOrPut(current) { mutableListOf() }
            val idx = bucket.indexOfFirst { it.code == code }
            if (idx >= 0) {
                if (q > bucket[idx].quality) bucket[idx] = SavelinkEntry(code, label, q)
            } else {
                bucket.add(SavelinkEntry(code, label, q))
            }
        }

        val epRegex = Regex("Epi-?\\s*(\\d{1,3})(?:-(\\d{1,3}))?", RegexOption.IGNORE_CASE)
        // Series signal: title/URL hints OR multiple per-episode sections
        // ("Download Now Epi-55", "Download Now Epi-54", …) — the structural
        // check catches titles like "S01E55" that evade word-boundary regexes.
        val epSectionCount = sections.keys.count { epRegex.containsMatchIn(it) }
        val isSeries = isSeries(url, title) || epSectionCount >= 2

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            sections.forEach { (secTitle, links) ->
                if (links.isEmpty()) return@forEach
                val epNum = epRegex.find(secTitle)?.groupValues?.get(1)?.toIntOrNull()
                val qualities = links.mapNotNull { it.quality.takeIf { q -> q > 0 } }
                    .distinct().sortedDescending().joinToString("/") { "${it}p" }
                val data = links.joinToString("|") { it.toJson() }
                episodes.add(newEpisode(data) {
                    name = when {
                        epNum != null -> "Episode $epNum" + if (qualities.isNotBlank()) " · $qualities" else ""
                        secTitle.equals("Download Now", true) -> "Full" + if (qualities.isNotBlank()) " · $qualities" else ""
                        else -> secTitle
                    }
                    if (epNum != null) this.episode = epNum
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
            val all = sections.values.flatten().distinctBy { it.code }
            val data = all.joinToString("|") { it.toJson() }
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
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
        val entries = data.split("|")
            .mapNotNull { tryParseJson<SavelinkEntry>(it) }
            .filter { !it.code.isNullOrBlank() }
        if (entries.isEmpty()) return false

        var found = false
        for (entry in entries) {
            val hosts = try {
                resolveSavelinkHosts(entry.code!!)
            } catch (e: Exception) {
                emptyList()
            }
            for (host in hosts) {
                try {
                    resolveCustomExtractor(host, subtitleCallback, callback)
                    found = true
                } catch (e: Exception) {
                    // try the next host
                }
            }
        }
        return found
    }

    /** Follows one savelinks.me/view/<code> page and returns the file-host URLs. */
    private suspend fun resolveSavelinkHosts(code: String): List<String> {
        val viewUrl = "$SAVELINKS_URL/view/$code"
        val (cf, _) = MlsbdStorage.getCfCookie()
        val headers = fetchHeaders(cf) + mapOf("Referer" to "$mainUrl/")
        val res = app.get(viewUrl, headers = headers, timeout = 45)
        if (!res.isSuccessful) return emptyList()
        return res.document.select("a[href]")
            .map { it.attr("abs:href").ifBlank { it.attr("href") } }
            .filter { it.startsWith("http") }
            .filter { link ->
                val l = link.lowercase()
                !l.contains("savelinks.me") && !l.contains("mlsbd.co") &&
                    !l.contains("t.me") && !l.contains("telegram") &&
                    !l.contains("whatsapp") && !l.contains("facebook") &&
                    !l.contains("twitter") && !l.contains("javascript:")
            }
            .distinctBy { it.substringBefore("?") }
    }

    /** Host-based dispatch into the dedicated ExtractorApis. */
    private suspend fun resolveCustomExtractor(
        link: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val l = link.lowercase()
        when {
            "pixeldrain.com" in l -> resolvePixeldrain(link, callback)
            "filepress" in l || "filebee" in l -> FilePress.getUrl(link, mainUrl, subtitleCallback, callback)
            "multicloud" in l -> MultiCloudLinks.getUrl(link, mainUrl, subtitleCallback, callback)
            "gdxfiles" in l -> GDxFiles.getUrl(link, mainUrl, subtitleCallback, callback)
            "gdflix" in l -> GDFlix.getUrl(link, mainUrl, subtitleCallback, callback)
            "hubcloud" in l || "hubdrive" in l -> HubCloud.getUrl(link, mainUrl, subtitleCallback, callback)
            "shikshakdaak" in l -> Shikshakdaak.getUrl(link, mainUrl, subtitleCallback, callback)
            "gofile" in l -> Gofile.getUrl(link, mainUrl, subtitleCallback, callback)
            "filesdl" in l -> Filesdl.getUrl(link, mainUrl, subtitleCallback, callback)
            else -> { /* unknown host — skip silently (ads etc.) */ }
        }
    }

    private fun resolvePixeldrain(
        link: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = link.substringAfter("pixeldrain.com/").trimStart('/')
            .substringBefore("?").substringBefore("/").trim()
        if (id.isBlank()) return
        val url = if (id.startsWith("api/")) link else "https://pixeldrain.com/api/file/$id?download"
        emitDirect(url, "Pixeldrain", callback, "https://pixeldrain.com/")
    }

    private fun emitDirect(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
        referer: String,
    ) {
        callback(
            newExtractorLink(name, "$name [$label]", url) {
                this.quality = mlsbdQualityNum(url)
                this.referer = referer
            }
        )
    }
}
