package com.redowan

import android.util.Base64
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
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
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MovieBox — aoneroom.com mobile-BFF provider (AllForU MovieBox v86 architecture).
 *
 * The MovieBox Android app (com.community.oneroom / com.community.mbox.in) talks to
 * a pool of wefeed-mobile-bff API hosts. This provider replicates that client:
 *
 *  - crypto: x-client-token = "<ts>,md5(reversed(ts))" and x-tr-signature =
 *    "<ts>|2|b64(hmac-md5(KEY, canonical))" where canonical is
 *    "METHOD\naccept\nctype\nbodyLen\nts\nmd5(body[:100K])\npath?sortedQuery".
 *  - identity: global "oneroom" app client-info (works from any region — verified
 *    live; the India "mbox.in" identity 403s outside IN).
 *  - token: GET /tab/ranking-list answers with an x-user header carrying a JWT
 *    guest token, reused for 12h across all calls.
 *  - content: POST /subject-api/list (channelId 1=movies, 2=series, 1006=anime,
 *    9=live sports; classify/genre filters incl. "Hindi dub" / "Bengali dub"),
 *    POST /subject-api/search (v1 keyword search), /subject-api/get (detail with
 *    dubs[] audio tracks), /subject-api/season-info (series episodes).
 *  - playback: the play-info/resource endpoints return a 23s "notice" dummy mp4
 *    to unofficial clients. The REAL stream is resolved by the two-step chain
 *    /subject-api/resource/v2 (per-resolution resourceIds) ->
 *    /subject-api/resource-position/v2 (signed bcdn.hakunaymatata.com direct
 *    link). Verified live: 206 video/mp4, no special UA needed.
 *  - subtitles: /subject-api/get-ext-captions?resourceId=..&subjectId=..&episode=..
 *    returns CloudFront-signed .srt tracks per language.
 *  - audio: every entry in detail.dubs is its own subjectId, so each dub/language
 *    is emitted as a separate source label ("MovieBox · Hindi dub · 720p · 1.4 GB").
 *
 * cleanMode=true ("MovieBox Clean") hides obvious 18+ titles from home/search;
 * the regular MovieBox plugin shows everything.
 */
class MovieBoxProvider(private val cleanMode: Boolean = false) : MainAPI() {

    override var mainUrl = "https://api3.aoneroom.com"
    override var name = if (cleanMode) "MovieBox Clean" else "MovieBox"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie)
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override var lang = "en"

    companion object {
        private val SIGN_KEY: ByteArray =
            Base64.decode("76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O", Base64.DEFAULT)

        private val LANG_NAMES = mapOf(
            "en" to "English", "hi" to "Hindi", "ta" to "Tamil", "te" to "Telugu",
            "ml" to "Malayalam", "kn" to "Kannada", "bn" to "Bengali", "mr" to "Marathi",
            "pa" to "Punjabi", "gu" to "Gujarati", "ur" to "Urdu", "es" to "Spanish",
            "esla" to "Spanish (LatAm)", "pt" to "Portuguese", "ptbr" to "Portuguese (BR)",
            "fr" to "French", "de" to "German", "it" to "Italian", "ja" to "Japanese",
            "ko" to "Korean", "zh" to "Chinese", "ar" to "Arabic", "ru" to "Russian",
            "tr" to "Turkish", "id" to "Indonesian", "vi" to "Vietnamese", "th" to "Thai",
            "fil" to "Filipino", "fa" to "Persian"
        )

        /** Strong 18+ signals only — used by MovieBox Clean, never the full plugin. */
        private val ADULT_REGEX = Regex(
            "(?i)(porn|xxx|hentai|nude|nudity|erotic|lust stories|ullu|kooku|primeshots|" +
                "prime shots|vivamax|hotshots|hot shots|mastram|gandii|duheli|palang tod|" +
                "jabran|hotmasti|hotx|wowclub|nuefliks|fliz|besharams|hunt cinema|navrasa|" +
                "18\\s*\\+|adult\\s*web series)"
        )

        private const val TOKEN_TTL = 12L * 3600_000L
    }

    // ----------------------------------------------------------- device identity

    private val deviceId = randomHex(32)
    private val versionCode = listOf(50020117, 50020118, 50020119, 50020120, 50020121).random()
    private val device = listOf("23078RKD5C", "2201117TY", "2201117TG", "22101316G", "21121210G").random()
    private val androidVer = listOf("12", "13").random()
    private val gaid = listOf(8, 4, 4, 4, 12).joinToString("-") { randomHex(it) }

    private val ua =
        "com.community.oneroom/$versionCode (Linux; U; Android $androidVer; en_US; $device; " +
            "Build/RP1A.200720.011; Cronet/135.0.7012.3)"

    private val clientInfo = run {
        val info = linkedMapOf(
            "package_name" to "com.community.oneroom",
            "version_name" to "4.0.01.0813.03",
            "version_code" to versionCode,
            "os" to "android",
            "os_version" to androidVer,
            "install_ch" to "ps",
            "device_id" to deviceId,
            "install_store" to "ps",
            "gaid" to gaid,
            "brand" to "Redmi",
            "model" to device,
            "system_language" to "en",
            "net" to "NETWORK_WIFI",
            "region" to "US",
            "timezone" to "Asia/Kolkata",
            "sp_code" to "40401",
            "X-Play-Mode" to "2"
        )
        info.entries.joinToString(",", "{", "}") {
            val v = it.value
            val rendered = if (v is Number) v.toString() else "\"" + v.toString() + "\""
            "\"" + it.key + "\":" + rendered
        }
    }

    private fun randomHex(len: Int): String {
        val chars = "0123456789abcdef"
        return (1..len).map { chars.random() }.joinToString("")
    }

    // ------------------------------------------------------------------- crypto

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { String.format("%02x", it) }

    private fun md5Hex(s: String): String = md5Hex(s.toByteArray(Charsets.UTF_8))

    private fun xClientToken(): String {
        val ts = System.currentTimeMillis().toString()
        return "$ts,${md5Hex(ts.reversed())}"
    }

    private fun canonical(method: String, ctype: String, url: String, body: String?, ts: Long): String {
        val u = URI(url)
        var path = u.path ?: "/"
        if (path.isEmpty()) path = "/"
        val params = sortedMapOf<String, MutableList<String>>()
        for (part in (u.rawQuery ?: "").split('&')) {
            if (part.isEmpty()) continue
            val i = part.indexOf('=')
            val rawK = if (i >= 0) part.substring(0, i) else part
            val rawV = if (i >= 0) part.substring(i + 1) else ""
            params.getOrPut(URLDecoder.decode(rawK, "UTF-8")) { mutableListOf() }
                .add(URLDecoder.decode(rawV, "UTF-8"))
        }
        val query = params.flatMap { (k, vs) -> vs.map { "$k=$it" } }.joinToString("&")
        val canonUrl = if (query.isNotEmpty()) "$path?$query" else path
        var bodyLen = ""
        var bodyMd5 = ""
        if (body != null) {
            val raw = body.toByteArray(Charsets.UTF_8)
            bodyLen = raw.size.toString()
            bodyMd5 = md5Hex(if (raw.size > 102400) raw.copyOfRange(0, 102400) else raw)
        }
        return listOf(
            method.uppercase(),
            "application/json",
            ctype,
            bodyLen,
            ts.toString(),
            bodyMd5,
            canonUrl
        ).joinToString("\n")
    }

    private fun xTrSignature(method: String, ctype: String, url: String, body: String?): String {
        val ts = System.currentTimeMillis()
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(SIGN_KEY, "HmacMD5"))
        val sig = mac.doFinal(canonical(method, ctype, url, body, ts).toByteArray(Charsets.UTF_8))
        return "$ts|2|" + Base64.encodeToString(sig, Base64.NO_WRAP)
    }

    // ------------------------------------------------------------------ network

    private val hosts = listOf(
        "https://api3.aoneroom.com",
        "https://api6.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api6sg.aoneroom.com",
        "https://api.inmoviebox.com"
    )

    @Volatile
    private var activeHost = 0

    private val retryCodes = setOf(403, 406, 407, 429, 500, 502, 503, 504)

    private var cachedToken: String? = null
    private var tokenAt = 0L
    private val tokenMutex = Mutex()

    private fun apiHeaders(url: String, token: String?, post: Boolean, body: String?): Map<String, String> {
        val ctype = if (post) "application/json; charset=utf-8" else "application/json"
        val h = mutableMapOf(
            "user-agent" to ua,
            "accept" to "application/json",
            "content-type" to ctype,
            "connection" to "keep-alive",
            "x-client-token" to xClientToken(),
            "x-tr-signature" to xTrSignature(if (post) "POST" else "GET", ctype, url, body),
            "x-client-info" to clientInfo,
            "x-client-status" to "0",
            "accept-language" to "*",
            "sec-fetch-mode" to "cors"
        )
        if (!token.isNullOrEmpty()) h["Authorization"] = "Bearer $token"
        return h
    }

    /** The ranking-list ping doubles as the guest-token bootstrap (x-user header). */
    private suspend fun obtainToken(): String? = tokenMutex.withLock {
        val cached = cachedToken
        if (cached != null && System.currentTimeMillis() - tokenAt < TOKEN_TTL) {
            return@withLock cached
        }
        val res = pooledGet(
            "/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1",
            wantToken = false
        ) ?: return@withLock null
        val token = captureToken(res) ?: return@withLock null
        cachedToken = token
        tokenAt = System.currentTimeMillis()
        token
    }

    private fun captureToken(res: NiceResponse): String? {
        return try {
            val xu = res.headers["x-user"] ?: return null
            val parsed = tryParseJson<Map<String, Any?>>(xu) ?: return null
            (parsed["token"] as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun pooledGet(path: String, wantToken: Boolean = true): NiceResponse? {
        val token = if (wantToken) obtainToken() else null
        val start = activeHost
        for (i in hosts.indices) {
            val idx = (start + i) % hosts.size
            val url = hosts[idx] + path
            for (attempt in 0 until 2) {
                try {
                    val res = app.get(url, headers = apiHeaders(url, token, false, null), timeout = 25)
                    if (res.code !in retryCodes) {
                        captureToken(res)?.let {
                            cachedToken = it
                            tokenAt = System.currentTimeMillis()
                        }
                        activeHost = idx
                        return res
                    }
                } catch (_: Exception) {
                }
                delay(250)
            }
        }
        return null
    }

    private suspend fun pooledPost(path: String, body: String): NiceResponse? {
        val token = obtainToken()
        val start = activeHost
        for (i in hosts.indices) {
            val idx = (start + i) % hosts.size
            val url = hosts[idx] + path
            for (attempt in 0 until 2) {
                try {
                    val res = app.post(
                        url,
                        requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaType()),
                        headers = apiHeaders(url, token, true, body),
                        timeout = 25
                    )
                    if (res.code !in retryCodes) {
                        captureToken(res)?.let {
                            cachedToken = it
                            tokenAt = System.currentTimeMillis()
                        }
                        activeHost = idx
                        return res
                    }
                } catch (_: Exception) {
                }
                delay(250)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ parsing

    private fun rootOf(text: String): Map<String, Any?>? =
        tryParseJson<Map<String, Any?>>(text)

    @Suppress("UNCHECKED_CAST")
    private fun itemsOf(root: Map<String, Any?>): List<Map<String, Any?>> {
        val data = root["data"] as? Map<String, Any?> ?: return emptyList()
        (data["items"] as? List<Map<String, Any?>>)?.let { return it }
        (data["subjects"] as? List<Map<String, Any?>>)?.let { return it }
        val results = data["results"] as? List<Map<String, Any?>>
        if (results != null) {
            return results.mapNotNull { it["subject"] as? Map<String, Any?> }
        }
        return emptyList()
    }

    private fun isAdultItem(o: Map<String, Any?>, title: String): Boolean {
        val genre = o["genre"]?.toString() ?: ""
        return ADULT_REGEX.containsMatchIn("$title $genre")
    }

    private fun Map<String, Any?>.toSearchResponse(): SearchResponse? {
        val id = (this["subjectId"] ?: this["id"])?.toString() ?: return null
        if (id.isBlank()) return null
        val rawTitle = (this["title"] ?: this["name"] ?: this["postTitle"])?.toString() ?: return null
        val title = rawTitle.substringBefore('[').trim()
        if (title.isEmpty()) return null
        if (cleanMode && isAdultItem(this, title)) return null
        val type = (this["subjectType"] as? Number)?.toInt()
            ?: this["subjectType"]?.toString()?.toIntOrNull() ?: 1
        val isSeries = type == 2 || type == 6 || type == 7
        val cover = this["cover"] as? Map<String, Any?>
        val poster = cover?.get("url")?.toString()
            ?: this["coverUrl"]?.toString()?.takeIf { it.startsWith("http") }
        val rating = this["imdbRatingValue"]?.toString()?.toDoubleOrNull()
        return if (isSeries) {
            newTvSeriesSearchResponse(title, id, TvType.TvSeries) {
                posterUrl = poster
                score = Score.from(rating, 10)
            }
        } else {
            newMovieSearchResponse(title, id, TvType.Movie) {
                posterUrl = poster
                score = Score.from(rating, 10)
            }
        }
    }

    private fun parseSubjects(text: String): List<SearchResponse> {
        val root = rootOf(text) ?: return emptyList()
        return itemsOf(root).mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
    }

    // ----------------------------------------------------------------- mainPage

    override val mainPage = mainPageOf(
        "r|4516404531735022304" to "Trending",
        "l|1|All|All" to "Movies",
        "l|2|All|All" to "TV Series",
        "l|1006|All|All" to "Anime",
        "l|9|All|All" to "Live Sports & Events",
        "l|1|Hindi dub|All" to "Hindi Dubbed",
        "l|1|Bengali dub|All" to "Bengali Dubbed",
        "l|1|Hindi dub|Action" to "Action",
        "l|1|Hindi dub|Comedy" to "Comedy",
        "l|1|Hindi dub|Horror" to "Horror",
        "l|1|Hindi dub|Romance" to "Romance",
        "l|1|Hindi dub|Thriller" to "Thriller",
        "l|1|Hindi dub|Drama" to "Drama",
        "l|1|Hindi dub|Crime" to "Crime",
        "l|1|Hindi dub|Sci-Fi" to "Sci-Fi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val parts = request.data.split('|')
        val items = when (parts.getOrNull(0)) {
            "r" -> fetchRanking(parts.getOrElse(1) { "4516404531735022304" }, page)
            "l" -> fetchList(parts.getOrElse(1) { "1" }, parts.getOrElse(2) { "All" }, parts.getOrElse(3) { "All" }, page)
            else -> emptyList()
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private suspend fun fetchRanking(categoryType: String, page: Int): List<SearchResponse> {
        val path = "/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=" +
            enc(categoryType) + "&page=$page&perPage=18"
        val res = pooledGet(path) ?: return emptyList()
        return parseSubjects(res.text)
    }

    private fun listPayload(channelId: String, classify: String, genre: String, page: Int): String {
        val sb = StringBuilder("{")
        val entries = linkedMapOf<String, String>(
            "page" to page.toString(),
            "perPage" to "18",
            "channelId" to "\"$channelId\"",
            "classify" to "\"$classify\"",
            "country" to "\"All\"",
            "year" to "\"All\"",
            "genre" to "\"$genre\"",
            "sort" to "\"ForYou\""
        )
        sb.append(entries.entries.joinToString(",") { "\"${it.key}\":${it.value}" })
        sb.append("}")
        return sb.toString()
    }

    private suspend fun fetchList(channelId: String, classify: String, genre: String, page: Int): List<SearchResponse> {
        val res = pooledPost("/wefeed-mobile-bff/subject-api/list", listPayload(channelId, classify, genre, page))
            ?: return emptyList()
        return parseSubjects(res.text)
    }

    // ------------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val safe = query.replace("\\", "").replace("\"", "")
        val payload = "{\"keyword\":\"$safe\",\"type\":0,\"page\":1,\"pageSize\":24}"
        val res = pooledPost("/wefeed-mobile-bff/subject-api/search", payload) ?: return emptyList()
        return parseSubjects(res.text)
    }

    // --------------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse {
        val sid = url.substringAfterLast('/').substringBefore('?').trim()
        if (sid.isEmpty()) throw IllegalArgumentException("bad MovieBox id")
        val res = pooledGet("/wefeed-mobile-bff/subject-api/get?subjectId=${enc(sid)}")
            ?: throw RuntimeException("MovieBox API unreachable")
        val root = rootOf(res.text) ?: throw RuntimeException("bad MovieBox response")
        val d = root["data"] as? Map<String, Any?> ?: throw RuntimeException("MovieBox entry missing")

        val rawTitle = (d["title"] ?: d["postTitle"] ?: d["name"])?.toString() ?: "MovieBox $sid"
        val title = rawTitle.substringBefore('[').trim().ifEmpty { "MovieBox $sid" }
        val poster = (d["cover"] as? Map<String, Any?>)?.get("url")?.toString()
        val plotBase = d["description"]?.toString() ?: ""
        val year = d["releaseDate"]?.toString()?.take(4)?.toIntOrNull()
        val language = d["language"]?.toString()?.takeIf { it.isNotBlank() }
        val country = d["countryName"]?.toString()?.takeIf { it.isNotBlank() }
        val durationSec = (d["durationSeconds"] as? Number)?.toInt() ?: 0
        val genres = d["genre"]?.toString()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        val dubs = dubList(d, sid)
        val audioLine = dubs.joinToString(", ") { it.first }

        val plot = buildString {
            append(plotBase)
            val extra = mutableListOf<String>()
            if (!language.isNullOrEmpty()) extra.add("Language: $language")
            if (!country.isNullOrEmpty()) extra.add("Country: $country")
            if (durationSec > 60) extra.add("Duration: ${durationSec / 60} min")
            if (dubs.size > 1) extra.add("Audio: $audioLine")
            if (extra.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(extra.joinToString(" · "))
            }
        }

        val cast = (d["staffList"] as? List<Map<String, Any?>>)?.mapNotNull { s ->
            val n = s["name"]?.toString() ?: return@mapNotNull null
            Actor(n, s["avatarUrl"]?.toString() ?: "")
        }.orEmpty()

        val type = (d["subjectType"] as? Number)?.toInt() ?: d["subjectType"]?.toString()?.toIntOrNull() ?: 1
        val isSeries = type == 2 || type == 6 || type == 7

        if (!isSeries) {
            return newMovieLoadResponse(title, sid, TvType.Movie, "$sid|0|0") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres
                addActors(cast)
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasons = fetchSeasons(sid)
        for (s in seasons) {
            val sn = (s["se"] as? Number)?.toInt() ?: s["se"]?.toString()?.toIntOrNull() ?: 1
            val maxEp = (s["maxEp"] as? Number)?.toInt() ?: s["maxEp"]?.toString()?.toIntOrNull() ?: 0
            if (maxEp <= 0) continue
            for (e in 1..maxEp) {
                episodes.add(
                    newEpisode("$sid|$sn|$e") {
                        name = "S$sn · Episode $e"
                        this.season = sn
                        this.episode = e
                    }
                )
            }
        }
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode("$sid|1|1") {
                    name = "Episode 1"
                    this.season = 1
                    this.episode = 1
                }
            )
        }

        return newTvSeriesLoadResponse(title, sid, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = genres
            addActors(cast)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun dubList(d: Map<String, Any?>, sid: String): List<Pair<String, String>> {
        val raw = d["dubs"] as? List<Map<String, Any?>> ?: return listOf("Original Audio" to sid)
        val out = mutableListOf<Pair<String, String>>()
        for (dd in raw) {
            val dsid = dd["subjectId"]?.toString() ?: continue
            if (dsid.isBlank()) continue
            val code = dd["lanCode"]?.toString() ?: ""
            val lanName = dd["lanName"]?.toString()?.takeIf { it.isNotBlank() }
                ?: (LANG_NAMES[code] ?: "Audio")
            val original = dd["original"] == true
            val label = if (original) "Original Audio" else lanName
            out.add(label to dsid)
        }
        if (out.isEmpty()) out.add("Original Audio" to sid)
        return out.distinctBy { it.second }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun fetchSeasons(sid: String): List<Map<String, Any?>> {
        val res = pooledGet("/wefeed-mobile-bff/subject-api/season-info?subjectId=${enc(sid)}") ?: return emptyList()
        val root = rootOf(res.text) ?: return emptyList()
        val data = root["data"] as? Map<String, Any?> ?: return emptyList()
        return (data["seasons"] as? List<Map<String, Any?>>).orEmpty()
    }

    // ---------------------------------------------------------------- loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split('|')
        val sid = parts.getOrNull(0)?.trim() ?: return false
        if (sid.isEmpty()) return false
        val se = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val ep = parts.getOrNull(2)?.toIntOrNull() ?: 0

        // dubs come from a fresh detail fetch (each dub = its own subjectId)
        val detailRes = pooledGet("/wefeed-mobile-bff/subject-api/get?subjectId=${enc(sid)}")
            ?: return false
        val d = (rootOf(detailRes.text)?.get("data") as? Map<String, Any?>) ?: return false
        val dubs = dubList(d, sid)

        var links = 0
        val seenLinks = mutableSetOf<String>()
        val doneCaptions = mutableSetOf<String>()

        // original audio gets the full quality sweep, extra dubs one call each
        for ((dubIdx, dub) in dubs.take(4).withIndex()) {
            if (links >= 12) break
            val resolutions = if (dubIdx == 0) listOf(0, 720) else listOf(0)
            val seenRids = mutableSetOf<String>()
            for (resolution in resolutions) {
                if (links >= 12) break
                val rpath = "/wefeed-mobile-bff/subject-api/resource/v2?subjectId=${enc(dub.second)}" +
                    "&page=1&perPage=20&all=0&startPosition=1&endPosition=1&pagerMode=2" +
                    "&resolution=$resolution&se=$se&epFrom=$ep&epTo=$ep&isVip=true"
                val rres = pooledGet(rpath) ?: continue
                val rlist = (rootOf(rres.text)?.get("data") as? Map<String, Any?>)
                    ?.get("list") as? List<Map<String, Any?>> ?: continue
                for (r in rlist) {
                    if (links >= 12) break
                    val rid = r["resourceId"]?.toString() ?: continue
                    if (rid.isEmpty() || !seenRids.add(rid)) continue
                    val fallback = r["resourceLink"]?.toString() ?: ""

                    val ppath = "/wefeed-mobile-bff/subject-api/resource-position/v2?subjectId=" +
                        enc(dub.second) + "&resourceId=" + enc(rid) +
                        "&failUrl=" + enc(fallback) + "&failCode=404&resourceNum=0&isVip=true"
                    val pres = pooledGet(ppath) ?: continue
                    val plist = (rootOf(pres.text)?.get("data") as? Map<String, Any?>)
                        ?.get("list") as? List<Map<String, Any?>> ?: continue
                    val el = plist.firstOrNull() ?: continue

                    val link = el["resourceLink"]?.toString() ?: continue
                    if (isTrap(link, el)) continue
                    if (!seenLinks.add(link)) continue

                    val size = el["size"]?.toString()?.toLongOrNull()
                        ?: (el["size"] as? Number)?.toLong() ?: 0L
                    val res2 = (el["resolution"] as? Number)?.toInt()
                        ?: el["resolution"]?.toString()?.toIntOrNull() ?: 0
                    val codec = el["codecName"]?.toString() ?: ""
                    emitLink(link, dub.first, res2, size, codec, callback)
                    links++

                    if (doneCaptions.size < 8) {
                        emitCaptions(sid, rid, ep, subtitleCallback, doneCaptions)
                    }
                }
            }
        }
        return links > 0
    }

    /** MovieBox serves a 23s "notice" dummy to unofficial clients — filter it. */
    private fun isTrap(link: String, el: Map<String, Any?>): Boolean {
        if (link.isEmpty()) return true
        val l = link.lowercase()
        if (l.contains("macdn.aoneroom.com/other/")) return true
        if (l.contains("/notice.mp4")) return true
        if (l.contains("1c7de0bd3393702d9191801f15f88f8d")) return true
        if (l.contains("9a0461bc39da389663bf3dbb17091d3f")) return true
        if (l.contains("b164fbfb4347792950bdfbfb563d39d9")) return true
        val size = el["size"]?.toString()?.toLongOrNull() ?: (el["size"] as? Number)?.toLong() ?: 0L
        val dur = (el["duration"] as? Number)?.toLong() ?: el["duration"]?.toString()?.toLongOrNull() ?: 0L
        if (dur in 10..40 && size < 5_000_000) return true
        if (size in 1..1_500_000) return true
        return false
    }

    private suspend fun emitLink(
        link: String,
        langLabel: String,
        resolution: Int,
        size: Long,
        codec: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val type = if (link.substringBefore('?').endsWith(".m3u8")) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }
        val label = buildString {
            append(name).append(" · ").append(langLabel)
            append(" · ").append(if (resolution > 0) "${resolution}p" else "auto")
            if (codec.isNotBlank() && codec.lowercase() == "hevc") append(" · HEVC")
            val sizeTxt = fmtSize(size)
            if (sizeTxt.isNotEmpty()) append(" · ").append(sizeTxt)
        }
        SourceStats.record("MovieBox API")
        callback(
            newExtractorLink(name, SourceStats.decorate("MovieBox API", label), link, type) {
                this.quality = if (resolution > 0) resolution else 720
                this.referer = ""
                // bcdn.hakunaymatata.com answers HTTP 428 to browser UAs (Mozilla/5.0*),
                // which kills CloudStream playback with ExoPlayer 2004 "bad http status".
                // Send the MovieBox app UA — CDN-verified 200/206.
                this.headers = mapOf("User-Agent" to ua)
            }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun emitCaptions(
        sid: String,
        rid: String,
        ep: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        done: MutableSet<String>
    ) {
        try {
            val path = "/wefeed-mobile-bff/subject-api/get-ext-captions?resourceId=${enc(rid)}" +
                "&subjectId=${enc(sid)}&episode=$ep"
            val res = pooledGet(path) ?: return
            val data = rootOf(res.text)?.get("data") as? Map<String, Any?> ?: return
            val caps = data["extCaptions"] as? List<Map<String, Any?>> ?: return
            for (c in caps) {
                val u = c["url"]?.toString() ?: continue
                if (u.isEmpty() || !u.startsWith("http")) continue
                if (!done.add(u)) continue
                val label = c["lanName"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: c["lan"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: "Subtitle"
                subtitleCallback(SubtitleFile(label, u))
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun fmtSize(bytes: Long): String {
        if (bytes <= 0) return ""
        return when {
            bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.0f MB", bytes / 1_048_576.0)
            else -> String.format("%.0f KB", bytes / 1_048_576.0 * 1024)
        }
    }
}
