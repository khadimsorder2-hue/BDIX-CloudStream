package com.redowan

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pompom - PomPomBD.Com (https://www.teraboxpage.com/myknow/ryy3vt2hum)
 *
 * The site is a TeraBox "webmaster" page: a creator shares video resources
 * (each resource = one TeraBox share with surl) grouped in categories.
 * Everything is driven by the same JSON APIs the web SPA calls, all of them
 * work without any login:
 *
 *   /api/webmaster/home/query    -> page config + categories
 *   /api/webmaster/home/reslist  -> resource list per category (paged)
 *   /api/shorturlinfo            -> share metadata (shareid, uk, sign, timestamp)
 *   /share/list                  -> files inside the share (folder walk)
 *   /share/streaming             -> m3u8 playlist for a file (M3U8_FLV_264_*)
 *
 * Requirements discovered by live probing:
 *  - every API needs 3 cookies set by the myknow page load (csrfToken, browserid,
 *    TSID) plus jsToken + pcftoken embedded in the page HTML -> we do a session
 *    bootstrap fetch and refresh it once automatically if an API starts failing
 *  - the m3u8 segments (data.teraboxpage.com) need NO cookies -> ExoPlayer plays
 *    them directly
 *  - folders are walked up to 2 levels deep, max 60 videos per share
 */
open class PompomProvider : MainAPI() {
    override var mainUrl = "https://www.teraboxpage.com"
    override var name = "Pompom"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val hasQuickSearch = false
    override val instantLinkLoading = true
    override var lang = "bn"
    override val supportedTypes = setOf(
        TvType.NSFW,
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** PomPomBD.Com webmaster route */
        private const val WM_ROUTE = "ryy3vt2hum"

        private const val APP_ID = "250528"

        /** loadLinks data marker */
        private const val DATA_PREFIX = "POMPOM|"

        /** streaming type -> quality candidates (first working wins, deduped) */
        private val M3U8_TYPES = listOf(
            "M3U8_AUTO_720",
            "M3U8_FLV_264_720",
            "M3U8_FLV_264_1080",
            "M3U8_FLV_264_480"
        )

        private val VIDEO_EXT = listOf(
            ".mp4", ".mkv", ".ts", ".flv", ".mov", ".avi", ".webm", ".m4v"
        )

        /** folder walk limits */
        private const val MAX_DEPTH = 2
        private const val MAX_FILES = 60
        private const val PAGE_SIZE = 20
    }

    // ---------------- session bootstrap ----------------
    @Volatile private var jsToken: String? = null
    @Volatile private var pcftoken: String? = null
    @Volatile private var cookieHeader: String? = null

    private fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/myknow/$WM_ROUTE",
        "Accept" to "*/*"
    )

    private suspend fun ensureSession(force: Boolean = false) {
        if (!force && !jsToken.isNullOrBlank() && !cookieHeader.isNullOrBlank()) return
        val res = app.get("$mainUrl/myknow/$WM_ROUTE", headers = mapOf("User-Agent" to UA))
        val html = res.text

        // jsToken lives URL-encoded inside templateData:
        //   "jsToken":"function%20fn%28a%29%7B...%7D%3Bfn%28%22HEX%22%29"
        // the browser evals it -> window.jsToken = HEX
        val decoded = try {
            java.net.URLDecoder.decode(html, "UTF-8")
        } catch (e: Exception) {
            html
        }
        jsToken = Regex("fn\\(\"([0-9A-Fa-f]{40,})\"\\)").find(decoded)?.groupValues?.get(1)
            ?: Regex("fn%28%22([0-9A-Fa-f]{40,})%22%29").find(html)?.groupValues?.get(1)
        pcftoken = Regex("\"pcftoken\":\"([0-9a-f]+)\"").find(html)?.groupValues?.get(1)

        // capture the 3 session cookies (csrfToken, browserid, TSID)
        val setCookies = try {
            res.headers.values("set-cookie")
        } catch (e: Exception) {
            listOfNotNull(res.headers["set-cookie"])
        }
        cookieHeader = setCookies.mapNotNull { sc ->
            val kv = sc.substringBefore(";").trim()
            if (kv.contains("=") && kv.isNotBlank()) kv else null
        }.joinToString("; ").takeIf { it.isNotBlank() }
    }

    private suspend fun refreshSession() = ensureSession(force = true)

    private fun apiHeaders(): Map<String, String> {
        val h = baseHeaders().toMutableMap()
        cookieHeader?.let { h["Cookie"] = it }
        return h
    }

    private fun commonParams(): String {
        val t = jsToken ?: ""
        val p = pcftoken ?: ""
        return "clientfrom=h5&psign=0&pcftoken=$p&clienttype=0&channel=dubox" +
            "&app_id=$APP_ID&web=1&jsToken=$t"
    }

    private suspend fun apiGetText(url: String): String? {
        return try {
            val res = app.get(url, headers = apiHeaders())
            if (res.isSuccessful) res.text else null
        } catch (e: Exception) {
            null
        }
    }

    /** JSON API call with one automatic session refresh + retry on failure */
    private suspend fun api(path: String, query: String): JSONObject? {
        val first = apiGetText("$mainUrl$path?$query&${commonParams()}")
        var json = first?.let { parseJsonObj(it) }
        if (json != null && jInt(json, "errno") == 0) return json
        refreshSession()
        val second = apiGetText("$mainUrl$path?$query&${commonParams()}")
        val json2 = second?.let { parseJsonObj(it) }
        return json2 ?: json
    }

    private fun parseJsonObj(text: String): JSONObject? = try {
        JSONObject(text.trim())
    } catch (e: Exception) {
        null
    }

    private fun jStr(o: JSONObject, key: String): String {
        if (o.isNull(key)) return ""
        return try {
            val v = o.optString(key, "")
            if (v == "null") "" else v
        } catch (e: Exception) {
            ""
        }
    }

    private fun jArr(o: JSONObject, key: String): JSONArray? = try {
        o.optJSONArray(key)
    } catch (e: Exception) {
        null
    }

    /** type-safe int read: the APIs mix ints and numeric strings across endpoints */
    private fun jInt(o: JSONObject, key: String): Int {
        return try {
            when (val v = o.opt(key)) {
                is Number -> v.toInt()
                is String -> v.trim().toIntOrNull() ?: 0
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    /** type-safe double read (sell_price etc.) */
    private fun jDouble(o: JSONObject, key: String): Double {
        return try {
            when (val v = o.opt(key)) {
                is Number -> v.toDouble()
                is String -> v.trim().toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
        } catch (e: Exception) {
            0.0
        }
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun thumbsOf(o: JSONObject): String? {
        val t = o.optJSONObject("thumbs") ?: return null
        return listOf("url3", "url2", "url1", "icon").firstNotNullOfOrNull { jStr(t, it).takeIf { v -> v.isNotBlank() } }
    }

    private fun isVideoName(n: String): Boolean {
        val l = n.lowercase()
        return VIDEO_EXT.any { l.endsWith(it) }
    }

    private fun cleanName(n: String): String = n.substringBeforeLast('.').trim().ifBlank { n }

    // ---------------- HOME ----------------
    override val mainPage = mainPageOf(
        "/wm/0" to "All",
        "/wm/1" to "Desi",
        "/wm/2" to "New Desi"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val idx = request.data.removePrefix("/wm/").toIntOrNull() ?: 0
        val json = api(
            "/api/webmaster/home/reslist",
            "category_idx=$idx&page=$page&psize=$PAGE_SIZE&user_route=$WM_ROUTE"
        )
        val items = mutableListOf<SearchResponse>()
        val total: Int
        if (json != null && jInt(json, "errno") == 0) {
            val data = json.optJSONObject("data")
            val arr = data?.let { jArr(it, "resource_list") }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val r = resourceToResult(o) ?: continue
                    items.add(r)
                }
            }
            total = data?.let { jInt(it, "total") } ?: items.size
        } else {
            total = items.size
        }
        val hasNext = page * PAGE_SIZE < total
        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun resourceToResult(o: JSONObject): SearchResponse? {
        val surl = jStr(o, "surl")
        if (surl.isBlank()) return null
        val rawName = jStr(o, "name").ifBlank { "Video" }
        val paid = jDouble(o, "sell_price") > 0.0
        val title = if (paid) "$rawName | Paid" else rawName
        return newMovieSearchResponse(title, "$mainUrl/tblink/$surl", TvType.NSFW) {
            this.posterUrl = thumbsOf(o)
        }
    }

    // ---------------- SEARCH (client-side over the resource list) ----------------
    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        // no server-side search on the webmaster page -> walk the All list (3 pages max)
        if (page > 3) return newSearchResponseList(emptyList(), false)

        val json = api(
            "/api/webmaster/home/reslist",
            "category_idx=0&page=$page&psize=50&user_route=$WM_ROUTE"
        )
        val items = mutableListOf<SearchResponse>()
        var total = 0
        if (json != null && jInt(json, "errno") == 0) {
            val data = json.optJSONObject("data")
            total = data?.let { jInt(it, "total") } ?: 0
            val arr = data?.let { jArr(it, "resource_list") }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = jStr(o, "name")
                    if (!name.contains(query, ignoreCase = true)) continue
                    val r = resourceToResult(o) ?: continue
                    items.add(r)
                }
            }
        }
        val hasNext = page < 3 && page * 50 < total
        return newSearchResponseList(items, hasNext)
    }

    // ---------------- LOAD ----------------
    override suspend fun load(url: String): LoadResponse {
        val clean = url.substringBefore("?").trimEnd('/')
        val surl = clean.substringAfter("/tblink/", "")
            .ifBlank { if (clean.startsWith("http")) "" else clean.trimEnd('/') }
        if (surl.isBlank() || surl.contains("/")) {
            throw ErrorLoadingException("Invalid Pompom link")
        }
        return loadShare(surl)
    }

    private data class ShareVideo(
        val fid: String,
        val fileName: String,
        val thumb: String?
    )

    private suspend fun loadShare(surl: String): LoadResponse {
        val sui = api("/api/shorturlinfo", "shorturl=1$surl&root=1&scene=wm_home")
        if (sui == null || jInt(sui, "errno") != 0) {
            throw ErrorLoadingException("Share deleted or unavailable")
        }
        val shareid = jStr(sui, "shareid")
        val uk = jStr(sui, "uk_str").ifBlank { jStr(sui, "uk") }
        val sign = jStr(sui, "sign")
        val ts = jStr(sui, "timestamp")
        if (shareid.isBlank() || uk.isBlank() || sign.isBlank()) {
            throw ErrorLoadingException("Share metadata missing")
        }

        // root folder name (shorturlinfo root entry) -> series title
        var rootTitle = ""
        val rootArr = jArr(sui, "list")
        if (rootArr != null && rootArr.length() > 0) {
            val first = rootArr.optJSONObject(0)
            if (first != null && jInt(first, "isdir") == 1) {
                rootTitle = jStr(first, "server_filename")
            }
        }

        val videos = mutableListOf<ShareVideo>()
        walkShare(surl, null, 0, videos)

        if (videos.isEmpty()) {
            throw ErrorLoadingException("No video files in this share")
        }

        fun dataFor(v: ShareVideo): String =
            "$DATA_PREFIX$shareid|$uk|$sign|$ts|${v.fid}"

        if (videos.size == 1) {
            val v = videos.first()
            return newMovieLoadResponse(
                cleanName(v.fileName),
                "$mainUrl/tblink/$surl",
                TvType.NSFW,
                dataFor(v)
            ) {
                this.posterUrl = v.thumb
                this.backgroundPosterUrl = v.thumb
                this.plot = "PomPomBD.Com - TeraBox shared video, streamed directly (no login)."
                this.tags = listOf("Pompom")
            }
        }

        val episodes = mutableListOf<Episode>()
        videos.forEachIndexed { index, v ->
            episodes.add(
                newEpisode(dataFor(v)) {
                    this.name = cleanName(v.fileName)
                    this.episode = index + 1
                    this.posterUrl = v.thumb
                }
            )
        }
        return newTvSeriesLoadResponse(
            rootTitle.ifBlank {
                videos.first().fileName.substringBeforeLast('/').trim()
            }.ifBlank { "Pompom folder" },
            "$mainUrl/tblink/$surl",
            TvType.NSFW,
            episodes
        ) {
            this.posterUrl = videos.firstOrNull()?.thumb
            this.plot = "PomPomBD.Com folder - ${videos.size} videos, streamed directly (no login)."
            this.tags = listOf("Pompom")
        }
    }

    /**
     * Walk the share file tree: root listing first, then folders up to MAX_DEPTH
     * levels, collecting playable video files (cap MAX_FILES).
     */
    private suspend fun walkShare(
        surl: String,
        dirPath: String?,
        depth: Int,
        out: MutableList<ShareVideo>
    ) {
        if (out.size >= MAX_FILES) return
        val query = if (dirPath == null) {
            "shorturl=$surl&root=1&scene=wm_home&by=time&order=desc&page=1&num=$MAX_FILES&web=1"
        } else {
            "shorturl=$surl&scene=wm_home&by=time&order=desc&page=1&num=$MAX_FILES" +
                "&dir=${enc(dirPath)}&web=1"
        }
        val json = api("/share/list", query) ?: return
        if (jInt(json, "errno") != 0) return
        val arr = json.optJSONObject("data")?.let { jArr(it, "list") } ?: jArr(json, "list")
            ?: return
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_FILES) return
            val o = arr.optJSONObject(i) ?: continue
            val name = jStr(o, "server_filename").ifBlank { jStr(o, "path").substringAfterLast('/') }
            val path = jStr(o, "path")
            if (jInt(o, "isdir") == 1) {
                if (depth < MAX_DEPTH && path.isNotBlank()) {
                    walkShare(surl, path, depth + 1, out)
                }
                continue
            }
            val cat = jInt(o, "category")
            if (cat != 1 && !isVideoName(name)) continue
            if (jInt(o, "play_forbid") != 0) continue
            out.add(ShareVideo(jStr(o, "fs_id"), name, thumbsOf(o)))
        }
    }

    // ---------------- LINKS ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith(DATA_PREFIX)) return false
        val parts = data.removePrefix(DATA_PREFIX).split("|")
        if (parts.size < 5) return false
        val shareid = parts[0]
        val uk = parts[1]
        val sign = parts[2]
        val ts = parts[3]
        val fid = parts[4]
        if (shareid.isBlank() || uk.isBlank() || sign.isBlank() || fid.isBlank()) return false

        var found = false
        val seen = mutableSetOf<String>()
        for (type in M3U8_TYPES) {
            // exact same param set the web player sends (extra params can trip the API)
            val url = "$mainUrl/share/streaming?uk=$uk&shareid=$shareid&type=$type&fid=$fid" +
                "&sign=${enc(sign)}&timestamp=$ts&jsToken=${jsToken ?: ""}&esl=1&isplayer=1" +
                "&ehps=1&clienttype=0&app_id=$APP_ID&web=1&channel=dubox"
            val txt = apiGetText(url)?.trim().orEmpty()
            // success = a real playlist; failures come back as {"errno":..} JSON
            if (!txt.startsWith("#EXTM3U")) continue
            val marker = txt.take(160)
            if (!seen.add(marker)) continue // same playlist as another type

            val quality = when (type) {
                "M3U8_AUTO_720" -> Qualities.P720.value
                "M3U8_FLV_264_1080" -> Qualities.P1080.value
                "M3U8_FLV_264_720" -> Qualities.P720.value
                "M3U8_FLV_264_480" -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "$name - ${type.substringAfterLast('_')}",
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = quality
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "User-Agent" to UA,
                        "Referer" to "$mainUrl/"
                    )
                }
            )
            found = true
        }
        return found
    }
}
