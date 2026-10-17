package com.redowan

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
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
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * MovieLinkBD (movielinkbd.li family — "Largest Movie Download Site in Bangladesh").
 *
 * The old movieslinkbd.com posts are now empty stubs; real content lives on a
 * rotating *.movielinkbd.li subdomain that sits behind a Cloudflare managed
 * challenge. Architecture mirrors the battle-tested CNCVerse provider:
 *
 *  - Base resolution: hub (movielinkbd.com homepage carries a link to the
 *    current .li host) -> saved base in SharedPreferences -> known fallback.
 *  - Requests carry full Sec-Fetch header set; a Turnstile gate page is
 *    detected and solved inside a visible WebView dialog (one-time "Verify
 *    you are human" tap). The resulting cookie jar + WebView UA are persisted
 *    and reused for every later request (cf_clearance is UA-bound).
 *  - Post pages embed `script#mlbdInlinePlayerData` — a JSON manifest with
 *    episodes/sources/quality/audio that is decoded directly.
 *  - Download buttons (li.flex-item) point at external hosts resolved by the
 *    ExtractorApis in Extractors.kt (HubCloud, GDFlix, GDxFiles, …).
 *  - /p/ stream URLs are normalised and tagged with the mlbd_hp token found
 *    in the page JS; .m3u8 sources are emitted as HLS.
 */
class MovieLinkBDProvider : MainAPI() {

    override var mainUrl = FALLBACK_URL
    override var name = "MovieLinkBD"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.AnimeMovie)

    companion object {
        @Volatile
        var appContext: Context? = null

        private const val HUB_URL = "https://movielinkbd.com"
        private const val FALLBACK_URL = "https://sefcng.movielinkbd.li"
        private const val PREF_KEY = "movielinkbd_main_url"
        private const val PREFS_NAME = "com.lagradost.cloudstream3"
        private const val WEBVIEW_TIMEOUT_MS = 180_000L
    }

    @Volatile
    private var resolvedBase: String? = null

    @Volatile
    private var webCookies: String? = null

    @Volatile
    private var webViewUa: String? = null

    @Volatile
    private var hpToken: String? = null

    /** Serialises Turnstile solving so parallel home rows don't spawn dialogs. */
    private val verifyMutex = Mutex()

    private val cdnStreamRegex =
        Regex("^(https?://([a-z0-9.-]+)/p/[A-Za-z0-9._%-]+)(\\?.*)?$", RegexOption.IGNORE_CASE)

    private val hpTokenRegex = Regex("mlbd_hp=(?:\"\\s*\\+\\s*\")?([0-9]+\\.[0-9a-fA-F]+)")

    private val headers = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
    )

    /** Recently Updated first, then the priority language rows. */
    override val mainPage = mainPageOf(
        "/" to "Recently Updated",
        "/language/bangla" to "Bangla Movies",
        "/language/bangla-dubbed" to "Bangla Dubbed",
        "/language/hindi" to "Hindi Movies",
        "/type/movies" to "All Movies",
        "/type/series" to "All Web Series",
        "/language/dual-audio" to "Dual Audio",
        "/language/english" to "English Movies",
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

    // ------------------------------------------------------------- base url

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_NAME, 0)

    private fun savedBase(): String? {
        return try {
            prefs()?.getString(PREF_KEY, null)?.takeIf { it.contains("movielinkbd.li") }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveBase(base: String) {
        try {
            prefs()?.edit()?.putString(PREF_KEY, base)?.apply()
        } catch (e: Exception) {
            // ignore
        }
    }

    /** The hub homepage links to whatever *.movielinkbd.li host is current. */
    private suspend fun resolveFromHub(): String? {
        return try {
            val doc = app.get(HUB_URL, headers = headers, timeout = 30).document
            doc.select("a[href*='movielinkbd.li']").firstOrNull()?.attr("href")?.let { href ->
                if (href.contains("://")) {
                    val host = href.substringAfter("://").substringBefore('/').trim()
                    if (host.isNotBlank()) "https://$host" else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getBase(): String {
        resolvedBase?.let { return it }
        val base = resolveFromHub() ?: savedBase() ?: FALLBACK_URL
        resolvedBase = base
        mainUrl = base
        return base
    }

    // ------------------------------------------------------------ fetching

    private fun isTurnstileGate(html: String): Boolean {
        if (html.length > 12000) return false
        return listOf(
            "cf-turnstile",
            "Just a moment",
            "challenge-platform",
            "cf_chl_opt",
            "Attention Required",
        ).any { html.contains(it, ignoreCase = true) }
    }

    /** Merges the persisted WebView cookie jar + UA into the request headers. */
    private fun requestHeaders(): Map<String, String> {
        var h = headers
        val cookies = webCookies
        val ua = webViewUa
        if (!cookies.isNullOrBlank()) h = h + ("Cookie" to cookies)
        if (!ua.isNullOrBlank()) h = h + ("User-Agent" to ua)
        return h
    }

    private suspend fun httpGetDoc(url: String): Document? {
        // 1) plain attempt with whatever cookies we already hold
        try {
            val res = app.get(url, headers = requestHeaders(), timeout = 60)
            if (res.isSuccessful && !isTurnstileGate(res.text)) {
                resolvedBase?.let { saveBase(it) }
                return res.document
            }
        } catch (e: Exception) {
            // fall through
        }
        // 2) gated -> solve once inside the mutex, then retry
        return verifyMutex.withLock {
            try {
                val res = app.get(url, headers = requestHeaders(), timeout = 60)
                if (res.isSuccessful && !isTurnstileGate(res.text)) {
                    resolvedBase?.let { saveBase(it) }
                    return@withLock res.document
                }
            } catch (e: Exception) {
                // fall through to WebView
            }
            val html = fetchViaWebView(url)
            when {
                html != null && html.length > 500 -> Jsoup.parse(html, url)
                else -> null
            }
        }
    }

    /**
     * Shows the page in a visible WebView dialog so a Cloudflare Turnstile
     * challenge can be completed by tapping "Verify you are human" once.
     * Polls the DOM every second; resolves when the page is no longer a gate
     * (and for post pages, when the inline player manifest shows up), saving
     * the full cookie jar + WebView UA for all later requests.
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
                    container.isFocusable = true
                    container.isFocusableInTouchMode = true
                    container.addView(wv)

                    val wrapper = LinearLayout(ctx)
                    wrapper.orientation = LinearLayout.VERTICAL
                    val tip = TextView(ctx)
                    tip.text = "MovieLinkBD: tap \"Verify you are human\" to continue."
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
                            val cookies = cookieManager.getCookie(url) ?: ""
                            val hasClearance = cookies.contains("cf_clearance=")
                            val playerReady = html.contains("mlbdInlinePlayerData")
                            if ((playerReady || (hasClearance && !isTurnstileGate(html))) && html.length > 2000) {
                                webCookies = cookies.ifBlank { null }
                                finish(html)
                            } else {
                                handler.postDelayed({ poll() }, 1000L)
                            }
                        }
                    }

                    wv.webViewClient = WebViewClient()
                    wv.loadUrl(url)
                    handler.postDelayed({ poll() }, 1500L)
                    handler.postDelayed({ finish(null) }, WEBVIEW_TIMEOUT_MS)
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
                org.json.JSONTokener(raw).nextValue().toString()
            } catch (e: Exception) {
                raw.removeSurrounding("\"")
            }
        }
        return raw
    }

    // --------------------------------------------------------------- cards

    private fun parseCards(doc: Document): List<SearchResponse> {
        return doc.select("div.movie-card").mapNotNull { card ->
            val a = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.isBlank()) return@mapNotNull null
            val navJunk = listOf("/genre/", "/category/", "/language/", "/type/", "/search", "?page=")
                .any { href.contains(it) }
            if (navJunk) return@mapNotNull null
            val img = card.selectFirst("img") ?: return@mapNotNull null
            val title = (img.attr("title").ifBlank { img.attr("alt") }).trim()
                .ifBlank { card.text().trim() }
            if (title.isBlank()) return@mapNotNull null
            val poster: String? = img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { null }
            val tvType = typeFromUrl(href)
            if (tvType == TvType.Movie) {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, href, tvType) { this.posterUrl = poster }
            }
        }.distinctBy { it.url }
    }

    private fun typeFromUrl(url: String): TvType = when {
        url.contains("/series/") || url.contains("/anime/") -> TvType.TvSeries
        url.contains("/drama/") -> TvType.AsianDrama
        else -> TvType.Movie
    }

    // ----------------------------------------------------------- main page

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = getBase()
        val path = request.data
        val url = when {
            path == "/" && page == 1 -> "$base/"
            path == "/" -> "$base/page/$page"
            page == 1 -> base + path
            else -> "$base$path/page/$page"
        }
        val doc = httpGetDoc(url) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = parseCards(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val base = getBase()
        val doc = httpGetDoc("$base/search?q=" + URLEncoder.encode(q, "UTF-8")) ?: return emptyList()
        return parseCards(doc)
    }

    // ---------------------------------------------------------------- load

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MlbdSource(val url: String? = null, val label: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MlbdDownload(val url: String? = null, val label: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MlbdPayload(
        val sources: List<MlbdSource> = emptyList(),
        val downloads: List<MlbdDownload> = emptyList(),
    )

    private fun MlbdPayload.toJson(): String = mapper.writeValueAsString(this)

    /** Decodes the inline player manifest (JSON script tag). */
    private fun decodeManifest(el: org.jsoup.nodes.Element?): JSONObject? {
        if (el == null) return null
        val raw = el.data().ifBlank { el.html() }
        if (raw.isBlank()) return null
        return try {
            JSONObject(raw.trim())
        } catch (e: Exception) {
            // JS-style: find the outermost JSON object
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start >= 0 && end > start) {
                try { JSONObject(raw.substring(start, end + 1)) } catch (e2: Exception) { null }
            } else null
        }
    }

    private fun sourcesOf(arr: org.json.JSONArray?): List<MlbdSource> {
        if (arr == null) return emptyList()
        val out = mutableListOf<MlbdSource>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val u = s.optString("url", "")
            if (!u.startsWith("http")) continue
            val q = s.optString("quality_label", "").ifBlank {
                s.optString("quality", "").ifBlank { s.optString("label", "") }
            }
            val audio = s.optString("audio", "")
            val label = listOf(q, audio).filter { it.isNotBlank() }.joinToString(" · ")
            out.add(MlbdSource(u, label.ifBlank { null }))
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = httpGetDoc(url) ?: throw IllegalArgumentException("post unreachable: $url")
        val html = doc.html()
        hpTokenRegex.find(html)?.groupValues?.get(1)?.let { hpToken = it }

        val title = doc.selectFirst(".movie-info-view h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.title().trim().ifBlank { url }
        val poster: String? = doc.selectFirst(".image-container-view img")?.attr("src")?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst(".story-text")?.text()?.trim()?.ifBlank { null }

        // Info lines: "Genre : Drama", "IMDb : 7.5", …
        val infoValues = doc.select(".movie-extra-info p.info-line").mapNotNull { line ->
            val t = line.text().trim()
            val key = t.substringBefore(":").trim().lowercase()
            val value = t.substringAfter(":", "").trim()
            if (value.isBlank()) null else key to value
        }
        val tags = infoValues.filter { it.first in setOf("genre", "language", "quality") }
            .flatMap { it.second.split(",", "/") }
            .map { it.trim() }.filter { it.isNotBlank() }.distinct().ifEmpty { null }

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()

        val cast = doc.select(".mlbd-cast__p").take(12).mapNotNull { el ->
            val name = el.selectFirst("small")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val img = el.selectFirst("img")?.attr("abs:src")?.ifBlank { null }
            Actor(name, img)
        }.ifEmpty { null }

        val trailerId = doc.selectFirst("[data-mlbd-trailer]")?.attr("data-mlbd-trailer")
        val trailerUrl = trailerId?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http")) it else "https://www.youtube.com/watch?v=$it"
        }

        // Inline player manifest.
        val manifest = decodeManifest(doc.selectFirst("script#mlbdInlinePlayerData"))

        val downloads = doc.select("li.flex-item a[href]").mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (!href.startsWith("http")) return@mapNotNull null
            if (href.contains("movielinkbd")) return@mapNotNull null
            val text = (a.text() + " " + (a.parent()?.text() ?: "")).trim()
                .replace(Regex("\\s+"), " ")
            MlbdDownload(href, text.ifBlank { "Download" })
        }.distinctBy { it.url?.substringBefore("?") }

        val episodesArr = manifest?.optJSONArray("episodes")
        val directSources = sourcesOf(manifest?.optJSONArray("sources"))
        val isSeries = (episodesArr != null && episodesArr.length() > 0) ||
            typeFromUrl(url) != TvType.Movie

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val n = episodesArr?.length() ?: 0
            for (i in 0 until n) {
                val ep = episodesArr!!.optJSONObject(i) ?: continue
                val number = ep.optInt("number", i + 1)
                val season = ep.optInt("season", 1)
                val label = ep.optString("label", "").ifBlank { "Episode $number" }
                val sources = sourcesOf(ep.optJSONArray("sources"))
                if (sources.isEmpty()) continue
                val payload = MlbdPayload(sources, downloads).toJson()
                episodes.add(newEpisode(payload) {
                    name = label
                    this.episode = number
                    this.season = season
                })
            }
            if (episodes.isEmpty()) {
                // Series page without a manifest: fall back to one item with
                // the download buttons so links still resolve.
                episodes.add(newEpisode(MlbdPayload(directSources, downloads).toJson()) {
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
                if (cast != null) addActors(cast)
                if (trailerUrl != null) addTrailer(trailerUrl)
            }
        } else {
            val payload = MlbdPayload(directSources, downloads).toJson()
            newMovieLoadResponse(title, url, TvType.Movie, payload) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                if (cast != null) addActors(cast)
                if (trailerUrl != null) addTrailer(trailerUrl)
            }
        }
    }

    // ------------------------------------------------------------ loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val payload = tryParseJson<MlbdPayload>(data) ?: run {
            // payload may be a bare source JSON when emitted from fallbacks
            val single = tryParseJson<MlbdSource>(data)
            if (single != null) MlbdPayload(listOf(single), emptyList()) else null
        } ?: return false

        val base = getBase()
        var found = false

        // 1) Inline manifest sources (the site's own player streams).
        for (src in payload.sources) {
            val raw = src.url ?: continue
            var link = fixUrlDomain(raw, base)
            if (cdnStreamRegex.matches(link)) {
                link = withHpToken(link)
            }
            val isHls = link.substringBefore('?').endsWith(".m3u8")
            val label = src.label?.ifBlank { null }
            callback(
                newExtractorLink(
                    name,
                    "$name ${label ?: "Stream"}".trim(),
                    link,
                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = mlbdQualityNum(label ?: link)
                    this.referer = base + "/"
                }
            )
            found = true
        }

        // 2) External download hosts.
        for (dl in payload.downloads) {
            val link = dl.url ?: continue
            try {
                MlbdExtractors.resolve(link, name, base, subtitleCallback, callback)
                found = true
            } catch (e: Exception) {
                // next host
            }
        }
        return found
    }

    private fun fixUrlDomain(url: String, base: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        else -> base.trimEnd('/') + "/" + url.trimStart('/')
    }

    /** /p/ CDN streams need the mlbd_hp token from the page JS. */
    private fun withHpToken(link: String): String {
        val token = hpToken ?: return link
        val sep = if (link.contains('?')) "&" else "?"
        return link + sep + "mlbd_hp=" + URLEncoder.encode(token, "UTF-8")
    }
}

internal fun mlbdQualityNum(text: String): Int {
    if (text.isBlank()) return 0
    val m = Regex("(?i)\\b(2160|1440|1080|720|576|480|360|240)\\s*p?\\b|\\b4k\\b").find(text)
        ?: return 0
    val t = m.value.lowercase()
    return when {
        t == "4k" -> 2160
        t.endsWith("p") -> t.dropLast(1).toIntOrNull() ?: 0
        else -> t.toIntOrNull() ?: 0
    }
}
