package com.redowan

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import okhttp3.FormBody
import org.jsoup.Jsoup
import java.net.URI

/**
 * Fojik (fojik.site) — MLWBD-style Dooplay WordPress site (Hindi dual audio,
 * 480p–4K GDRive downloads).
 *
 * Home/search/load are plain WordPress. Download links sit behind a protected
 * relay chain:
 *   1) POST {FU, FN}          -> search.technews24.site/blog.php
 *   2) POST {FU2}             -> sharelink-*.shop/dld2.php?i=… (form relay)
 *   3) POST {FU2}             -> freethemesy.shop/dld2.php  (page holds var sss)
 *   4) POST {s: sss, v: tok}  -> <origin>/new/l/api/m       -> technews24 links page
 *   5) go2.php?i=…&hash=…     -> FU5/FU7 relays -> READWEB(sss/v/vurl)
 *   6) POST JSON {s,v} -> <origin>/l/api/m -> cdn-hub /url? -> /embed? 302 = direct file
 * Verified live 2026-10.
 * Every relay page is parsed generically (first <form> + hidden inputs), so a
 * host swap never breaks the chain.
 */
class FojikProvider : MainAPI() {
    override var mainUrl = "https://fojik.site"
    override var name = "Fojik"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        const val BLOG_ACTION = "https://search.technews24.site/blog.php"
    }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept-Language" to "bn,en-US;q=0.9,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        "movie" to "Latest Movies",
        "genre/action" to "Action",
        "genre/thriller" to "Thriller",
        "genre/comedy" to "Comedy",
        "genre/horror" to "Horror",
        "genre/romance" to "Romance",
        "genre/drama" to "Drama",
        "genre/science-fiction" to "Sci-Fi",
    )

    private fun parseCards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<SearchResponse>()
        val postRe = Regex("^https?://fojik\\.site/(movie|tvshows|series)/[^/]+/$")
        for (art in doc.select("article.item")) {
            var href = art.selectFirst(".data h3 a[href], h3 a[href]")?.absUrl("href").orEmpty()
            var title = art.selectFirst(".data h3 a[href], h3 a[href]")?.text()?.trim().orEmpty()
            val img = art.selectFirst(".poster img")
            if (href.isEmpty()) {
                val a2 = art.select("a[href]").firstOrNull { postRe.matches(it.absUrl("href")) } ?: continue
                href = a2.absUrl("href")
            }
            if (!postRe.matches(href) || !seen.add(href)) continue
            if (title.isEmpty()) title = img?.attr("alt").orEmpty().trim()
            if (title.length < 3) continue
            val poster = img?.attr("data-src")?.ifEmpty { img.attr("src") }
            out.add(newMovieSearchResponse(title, href, if (href.contains("tvshows")) TvType.TvSeries else TvType.Movie) {
                this.posterUrl = poster
            })
        }
        if (out.isEmpty()) {
            // search pages: bare a>img pairs
            for (a in doc.select("a[href]")) {
                val href = a.absUrl("href")
                if (!postRe.matches(href) || !seen.add(href)) continue
                val img = a.selectFirst("img") ?: continue
                val title = a.attr("title").ifEmpty { img.attr("alt") }.ifEmpty { a.text() }.trim()
                if (title.length < 3) continue
                out.add(newMovieSearchResponse(title, href, if (href.contains("tvshows")) TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = img.attr("data-src").ifEmpty { img.attr("src") }
                })
            }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            page == 1 -> "$mainUrl/${request.data}/"
            else -> "$mainUrl/${request.data}/page/$page/"
        }
        val doc = app.get(url, headers = headers, timeout = 30).document
        val items = parseCards(doc)
        return newHomePageResponse(request.name, items, hasNext = items.size >= 10)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.trim()}", headers = headers, timeout = 30).document
        return parseCards(doc)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FuRow(val quality: String, val FU: String, val FN: String, val action: String)

    private fun parseFuRows(doc: org.jsoup.nodes.Document): List<FuRow> {
        val rows = mutableListOf<FuRow>()
        for (f in doc.select("form")) {
            val fu = f.selectFirst("input[name=FU]")?.attr("value") ?: continue
            val fn = f.selectFirst("input[name=FN]")?.attr("value").orEmpty()
            val tr = f.closest("tr")
            val tds = tr?.select("td")?.map { it.text().trim() } ?: emptyList()
            rows.add(FuRow(tds.getOrNull(1).orEmpty(), fu, fn, f.attr("action").ifEmpty { BLOG_ACTION }))
        }
        return rows
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, timeout = 30).document
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val rows = parseFuRows(doc)
        // data: quality|FU@@action@@FN (pipe = quality, @@ = chain fields)
        val data = rows.joinToString(" ; ") {
            "${it.quality.ifEmpty { "Link" }}|${it.FU}@@${it.action}@@${it.FN}"
        }
        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private fun qNum(s: String): Int = Regex("(\\d{3,4})p").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** first <form> with hidden inputs from an html fragment */
    private data class Form(val action: String, val fields: Map<String, String>)

    private fun firstForm(html: String): Form? {
        val doc = Jsoup.parse(html)
        val f = doc.select("form").firstOrNull { it.select("input[name=FU2], input[name=FU]").isNotEmpty() }
            ?: return null
        val fields = f.select("input[name]").associate { it.attr("name") to it.attr("value") }
        return Form(f.absUrl("action").ifEmpty { f.attr("action") }, fields)
    }

    private suspend fun postForm(url: String, fields: Map<String, String>, referer: String): NiceResponse {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return app.post(url, requestBody = body, headers = mapOf("Referer" to referer, "User-Agent" to headers["User-Agent"]!!), timeout = 25000L)
    }

    /**
     * go2.php?i=..&hash=.. chain (verified live 2026-10):
     *   GET go2            -> auto-form FU5 (sharelink-*.shop/dld2.php)
     *   POST FU5           -> auto-form FU7 (.../dld2/)
     *   POST FU7           -> READWEB page: sss + v + vurl=atob('/l/api/m')
     *   POST JSON {s,v}    -> <origin>/l/api/m -> cdn.cdn-hub.xyz/url?photo=...
     *   /url? -> /embed?   -> 302 -> googleusercontent DIRECT file (playable)
     * Returns the final playable URL or null.
     */
    private suspend fun resolveGoChain(goUrl: String, referer: String): String? {
        return try {
            var g = app.get(goUrl, headers = mapOf("Referer" to referer, "User-Agent" to headers["User-Agent"]!!), timeout = 20000L)
            var gform = firstForm(g.text)
            var gguard = 0
            while (gform != null && gform.action.isNotEmpty() && gguard++ < 4 &&
                !g.text.contains(Regex("var\\s+sss"))
            ) {
                g = postForm(gform.action, gform.fields, g.url)
                gform = firstForm(g.text)
            }
            val sss = Regex("var\\s+sss\\s*=\\s*'([^']+)'").find(g.text)?.groupValues?.get(1)
                ?: return null
            val v = Regex("v:\\s*'([^']+)'").find(g.text)?.groupValues?.get(1).orEmpty()
            val vurlB64 = Regex("var\\s+vurl\\s*=\\s*atob\\('([^']+)'\\)").find(g.text)?.groupValues?.get(1)
                ?: return null
            val vurl = String(java.util.Base64.getDecoder().decode(vurlB64 + "==="))
            val origin = URI(g.url).let { "${it.scheme}://${it.host}" }
            // JSON api -> cdn-hub /url?photo=...
            val destResp = app.post(
                origin + vurl,
                json = mapOf("s" to sss, "v" to v),
                headers = mapOf(
                    "Referer" to g.url,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to headers["User-Agent"]!!
                ),
                timeout = 20000L
            ).text.trim()
            if (!destResp.startsWith("http")) return null
            val embed = if (destResp.contains("/url?")) destResp.replace("/url?", "/embed?") else destResp
            // follow the one-shot 302 NOW — the Location is a long-lived
            // googleusercontent signed url that plays with any UA/referer
            val fr = app.get(embed, headers = mapOf("Referer" to origin + "/", "User-Agent" to headers["User-Agent"]!!), timeout = 25000L)
            if (fr.url.contains("google")) fr.url else embed
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        for (item in data.split(" ; ").take(3)) {
            val pipe = item.indexOf("|")
            if (pipe < 0) continue
            val quality = item.substring(0, pipe).trim()
            val parts = item.substring(pipe + 1).split("@@")
            val FU = parts.getOrNull(0)?.trim() ?: continue
            val action = parts.getOrNull(1)?.trim().orEmpty()
            val FN = parts.getOrNull(2)?.trim().orEmpty()

            try {
                // hop 1 — blog.php
                var resp = postForm(action.ifEmpty { BLOG_ACTION }, mapOf("FU" to FU, "FN" to FN), mainUrl)
                var form = firstForm(resp.text)
                var guard = 0
                // hops 2..n — relay forms carrying FU2 (stop at the sss page)
                while (form != null && form.action.isNotEmpty() && form.fields.containsKey("FU2") &&
                    guard++ < 6 && !resp.text.contains(Regex("var\\s+sss"))
                ) {
                    resp = postForm(form.action, form.fields, resp.url)
                    form = firstForm(resp.text)
                }

                val sss = Regex("var\\s+sss\\s*=\\s*'([^']+)'").find(resp.text)?.groupValues?.get(1)
                if (sss != null) {
                    val origin = URI(resp.url).let { "${it.scheme}://${it.host}" }
                    // final api — returns destination url as plain text
                    val dest = postForm(
                        "$origin/new/l/api/m",
                        mapOf("s" to sss, "v" to (Regex("v:\\s*'([^']+)'").find(resp.text)?.groupValues?.get(1) ?: "6ad99d6354708")),
                        resp.url
                    ).text.trim()
                    if (dest.startsWith("http")) {
                        // links page → go.php/go2.php hops
                        val linksDoc = app.get(dest, headers = mapOf("Referer" to "https://freethemesy.shop/", "User-Agent" to headers["User-Agent"]!!), timeout = 25000L)
                        val goLinks = Regex("https://[a-z.]*technews24\\.(?:site|me)/go2?\\.php\\?i=[A-Za-z0-9+/=_-]+(?:&hash=[A-Za-z0-9+/=_%-]+)?")
                            .findAll(linksDoc.text.replace("&amp;", "&")).map { it.value }.distinct().toList()
                        if (goLinks.isEmpty()) {
                            // links page itself might already be the final host page
                            loadExtractor(dest, mainUrl, subtitleCallback, callback)
                            continue
                        }
                        // ONE go2 link per quality row — every go link of a row
                        // funnels into the same cdn-hub file, so resolve only
                        // the first (the chain is ~8 requests deep).
                        goLinks.firstOrNull()?.let { go ->
                            val resolved = resolveGoChain(go, dest)
                            if (resolved != null) {
                                callback(
                                    ExtractorLink(
                                        this.name,
                                        "${this.name} ${quality.ifEmpty { "Link" }} Google Server",
                                        resolved, "", qNum(quality), type = ExtractorLinkType.VIDEO
                                    )
                                )
                            } else {
                                // unresolved — expose the relay as a page link
                                callback(
                                    ExtractorLink(
                                        this.name, "${this.name} ${quality.ifEmpty { "Link" }} Page",
                                        go, dest, 0, type = ExtractorLinkType.VIDEO
                                    )
                                )
                            }
                        }
                        continue
                    }
                }
                // chain unresolved — expose the last relay as a link
                form?.let { f ->
                    if (f.action.startsWith("http")) {
                        callback(
                            ExtractorLink(
                                this.name, "${this.name} ${quality.ifEmpty { "Link" }} Page",
                                f.action, mainUrl, 0, type = ExtractorLinkType.VIDEO
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // skip broken row
            }
        }
        return true
    }
}
