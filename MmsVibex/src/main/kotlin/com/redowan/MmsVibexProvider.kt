package com.redowan

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MainPageData
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
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

/**
 * MmsVibeX (https://mmsvibex.fit) - NSFW provider
 *
 * Features:
 *  - Home page with ALL sorts (latest / popular / most-viewed / longest / random)
 *  - ALL categories (Bangladeshi, Indian, Desi, Pakistani, Sri Lankan, Nepali, Arab, Cam, Hidden Cam, Gay/Shemale, Pashto, Pathan, Faphouse, Others)
 *  - Albums (actor/playlists) as series with each video as an episode
 *  - Video info page shows the site's reference stills (clean main thumb + clean frames)
 *    as big tap-able cards right under the info; each card opens a photo page with the
 *    full-size image as the big poster, and the result screen's built-in
 *    "open in browser" button shows it TRUE FULLSCREEN (CloudStream has no native
 *    in-app image viewer, this is the only fullscreen path)
 *  - Videos that belong to an album show the album's other videos under the info
 *    (recommendations row: in-page album preview + full album fetch with pagination)
 *  - Dedicated photo albums: "Photo Albums" home row, per-video photo album cards on
 *    the info page, /video-photos/<video> listings, and full photo galleries where
 *    every photo is a big card -> photo page -> fullscreen via browser button
 *  - Paginated search via the WordPress REST API (clean JSON, no ads)
 *  - Ad-free playback: direct mp4 extracted from the site player config (mvp-config),
 *    VAST/magsrv ad calls are never touched
 *  - Multi-source: every quality/variant on the player page is returned
 */
open class MmsVibexProvider : MainAPI() {
    override var mainUrl = "https://mmsvibex.fit"
    override var name = "MmsVibeX"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val instantLinkLoading = true
    override var lang = "bn"
    // PURE NSFW ONLY. CloudStream's filterProviderByPreferredMedia() hides providers whose
    // supportedTypes contain no non-NSFW type from home rows, the providers grid and search
    // unless the user opts in to the NSFW media type. A mixed set (NSFW+Movie+TvSeries)
    // leaks the provider into normal movie/TV sections.
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** per_page for the REST API listing */
        private const val PER_PAGE = 24

        /** loadLinks marker: photo pages have no playable video */
        private const val PHOTO_DATA = "MMSV_PHOTOS_NO_VIDEO"

        /** max photos rendered on a gallery info page */
        private const val MAX_PHOTOS = 120

        /** max album pages walked for sibling videos */
        private const val ALBUM_PAGES = 4

        /** max items in the recommendations row */
        private const val MAX_RECS = 60
    }

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    // ---------------- ALL sorts + categories + albums + photos ----------------
    override val mainPage = mainPageOf(
        // --- sorting ---
        "/?filter=latest" to "Latest",
        "/?filter=popular" to "Popular",
        "/?filter=most-viewed" to "Most Viewed",
        "/?filter=longest" to "Longest",
        "/?filter=random" to "Random",
        // --- albums (actor / playlists) ---
        "/actors/" to "Albums",
        // --- photo albums (galleries) ---
        "/photos/" to "Photo Albums",
        // --- categories ---
        "/category/bangladesi/" to "Bangladeshi",
        "/category/indian/" to "Indian",
        "/category/desi/" to "Desi",
        "/category/pakistani/" to "Pakistani",
        "/category/sri-lankan-sl/" to "Sri Lankan",
        "/category/nepali/" to "Nepali",
        "/category/arab/" to "Arab",
        "/category/cam/" to "Cam",
        "/category/hidden-cam/" to "Hidden Cam",
        "/category/gay-shemale/" to "Gay/Shemale",
        "/category/pashto/" to "Pashto",
        "/category/pathan/" to "Pathan",
        "/category/faphouse/" to "Faphouse",
        "/category/others/" to "Others"
    )

    // ---------------- URL builders ----------------
    private fun pageUrl(data: String, page: Int): String {
        return when {
            data.startsWith("/actors") ->
                if (page <= 1) "$mainUrl/actors/" else "$mainUrl/actors/page/$page/"
            data.startsWith("/photos") ->
                if (page <= 1) "$mainUrl/photos/" else "$mainUrl/photos/page/$page/"
            data.startsWith("/category/") ->
                if (page <= 1) "$mainUrl$data" else "$mainUrl${data}page/$page/"
            data.startsWith("/?filter=") ->
                if (page <= 1) "$mainUrl$data" else "$mainUrl/page/$page/?${data.substringAfter("?")}"
            else ->
                if (page <= 1) "$mainUrl$data" else "$mainUrl/page/$page/"
        }
    }

    // ---------------- HOME ----------------
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = pageUrl(request.data, page)
        val doc = app.get(url, headers = headers).document

        val items: List<SearchResponse> = when {
            request.data.startsWith("/actors") ->
                doc.select("article.thumb-block").mapNotNull { toAlbumResult(it) }
            request.data.startsWith("/photos") ->
                doc.select("article.mms-photo-card")
                    .filterNot { isAdCard(it) }
                    .mapNotNull { toPhotoAlbumResult(it) }
            else ->
                doc.select("article.video-preview-item")
                    .filterNot { isAdCard(it) }
                    .mapNotNull { toVideoResult(it) }
        }

        return newHomePageResponse(
            request.name,
            items,
            items.isNotEmpty()
        )
    }

    private fun isAdCard(post: Element): Boolean =
        post.classNames().any { it.startsWith("wpst-provider") }

    private fun cleanImg(src: String?): String? {
        if (src.isNullOrBlank()) return null
        if (src.contains("px.gif") || src.startsWith("data:")) return null
        return src
    }

    private fun normUrl(u: String?): String? {
        if (u.isNullOrBlank()) return null
        return if (u.startsWith("http")) u else mainUrl + (if (u.startsWith("/")) u else "/$u")
    }

    private fun imgSrc(el: Element?): String? {
        if (el == null) return null
        return cleanImg(el.attr("src")) ?: cleanImg(el.attr("data-src"))
    }

    private fun sameUrl(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return a.trimEnd('/') == b.trimEnd('/')
    }

    // ---------------- card mappers ----------------
    private fun toVideoResult(post: Element): SearchResponse? {
        val a = post.selectFirst("a[href]") ?: return null
        val url = normUrl(a.attr("href")) ?: return null

        val title = post.selectFirst(".title")?.text()?.takeIf { it.isNotBlank() }
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: post.selectFirst(".entry-header")?.text()?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = imgSrc(post.selectFirst(".post-thumbnail-container img"))
            ?: imgSrc(post.selectFirst(".post-thumbnail img"))
            ?: imgSrc(post.selectFirst("img"))
            ?: cleanImg(post.attr("data-main-thumb"))

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun toAlbumResult(post: Element): SearchResponse? {
        val a = post.selectFirst("a[href]") ?: return null
        var url = a.attr("href")
        if (url.isBlank()) return null

        // theme links look like https://mmsvibex.fit?actors=slug -> normalize to /actor/slug/
        if (url.contains("?actors=")) {
            val slug = url.substringAfter("?actors=").trimEnd('/').trimEnd('&')
            if (slug.isBlank()) return null
            url = "$mainUrl/actor/$slug/"
        } else if (!url.startsWith("http")) {
            url = mainUrl + (if (url.startsWith("/")) url else "/$url")
        }

        val title = post.selectFirst(".actor-title")?.text()?.takeIf { it.isNotBlank() }
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: return null

        val poster = cleanImg(post.selectFirst("img")?.attr("data-src"))
            ?: cleanImg(post.selectFirst("img")?.attr("src"))
            ?: cleanImg(post.attr("data-main-thumb"))

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    /** photo album card (used on /photos/, /video-photos/<video>/ listings) */
    private fun toPhotoAlbumResult(post: Element): SearchResponse? {
        val a = post.selectFirst("a[href]") ?: return null
        val url = normUrl(a.attr("href")) ?: return null
        if (!url.contains("/photos/")) return null

        val title = post.selectFirst(".mms-photo-card-title")?.text()?.takeIf { it.isNotBlank() }
            ?: a.attr("aria-label").takeIf { it.isNotBlank() }
            ?: post.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = imgSrc(post.selectFirst(".mms-photo-card-image"))
            ?: imgSrc(post.selectFirst("img"))

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // ---------------- SEARCH (WordPress REST API, paginated) ----------------
    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)

        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        val apiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$enc&per_page=$PER_PAGE&page=$page&_embed"

        val res = app.get(apiUrl, headers = headers)
        val items = AppUtils.parseJson<List<WpPost>>(res.text).orEmpty()
            .filter { it.link?.contains("/photos/") != true }
            .filter { it.link?.contains("/video-photos/") != true }
            .mapNotNull { toSearchResult(it) }

        val hasNext = res.headers["x-wp-totalpages"]?.toIntOrNull()?.let { page < it } ?: (items.size >= PER_PAGE)
        return newSearchResponseList(items, hasNext)
    }

    private fun toSearchResult(post: WpPost): SearchResponse? {
        val link = post.link ?: return null
        val title = post.title?.rendered
            ?.replace("&amp;", "&")
            ?.replace("&#038;", "&")
            ?.replace("&quot;", "\"")
            ?.replace("&#8217;", "'")
            ?.trim()
            ?: return null
        val thumb = post.embedded?.featuredMedia?.firstOrNull()?.sourceUrl
        return newMovieSearchResponse(title, link, TvType.NSFW) {
            this.posterUrl = thumb
        }
    }

    // ---------------- LOAD ----------------
    override suspend fun load(url: String): LoadResponse {
        val clean = url.substringBefore("?")
        return when {
            isImageUrl(clean) -> loadSinglePhoto(clean)
            clean.contains("/video-photos/") -> loadPhotoListing(clean)
            clean.contains("/photos/") -> {
                // "/photos/" and "/photos/page/N/" are listings, "/photos/<slug>/" is a gallery
                val after = clean.substringAfter("/photos/").trimEnd('/')
                if (after.isBlank() || after.startsWith("page/")) loadPhotoListing(clean)
                else loadPhotoGallery(clean)
            }
            clean.contains("/actor/") -> loadAlbum(clean)
            else -> loadVideo(clean)
        }
    }

    // ---------- SINGLE PHOTO (fullscreen-friendly detail page) ----------
    private fun isImageUrl(u: String): Boolean =
        Regex("\\.(jpe?g|png|webp)$", RegexOption.IGNORE_CASE)
            .containsMatchIn(u.trimEnd('/').substringBefore("?"))

    /**
     * CloudStream has no native in-app image viewer (actors render as small circles,
     * plot images don't render, posters are not click-able), so every photo gets its
     * own detail page: the image is the BIG poster + page background, and the result
     * screen's built-in "open in browser" button opens the raw full-size image for
     * true fullscreen viewing.
     */
    private suspend fun loadSinglePhoto(url: String): LoadResponse {
        return newMovieLoadResponse("Photo", url, TvType.NSFW, PHOTO_DATA) {
            this.posterUrl = url
            this.backgroundPosterUrl = url
            this.plot =
                "Full-size photo. Tap the open-in-browser icon at the top of this page to view it in true fullscreen."
            this.tags = listOf("Photo")
        }
    }

    // ---------- PHOTO GALLERY (/photos/<slug>/) : full image grid on the info page ----------
    private suspend fun loadPhotoGallery(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?: "Photo Album"

        // full-size images first (a[href*=.../images/full/NNNN.jpg]), fall back to card thumbs
        val photos = mutableListOf<String>()
        for (a in doc.select("a[href*=\"/image-albums/\"]")) {
            val href = a.attr("href")
            if (href.contains("/images/full/") && !photos.contains(href)) photos.add(href)
        }
        if (photos.isEmpty()) {
            for (im in doc.select("img.mms-image-album-card-image")) {
                val s = imgSrc(im)
                if (s != null && !photos.contains(s)) photos.add(s)
            }
        }
        val capped = photos.take(MAX_PHOTOS)

        val poster = capped.firstOrNull()
            ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")

        // videos related to this album (a.mms-related-video-card)
        val related = mutableListOf<SearchResponse>()
        for (a in doc.select("a.mms-related-video-card")) {
            val link = normUrl(a.attr("href")) ?: continue
            if (!link.startsWith("http") || link.contains("/photos/") || link.contains("/tag/")) continue
            val name = a.selectFirst("strong")?.text()?.takeIf { it.isNotBlank() }
                ?: a.attr("aria-label").takeIf { it.isNotBlank() }
                ?: "Related video"
            val thumb = imgSrc(a.selectFirst(".mms-related-video-thumb img"))
            related.add(
                newMovieSearchResponse(name, link, TvType.NSFW) {
                    this.posterUrl = thumb
                }
            )
        }
        val relatedClean = related.distinctBy { it.url }.filterNot { sameUrl(it.url, url) }

        // every photo as a big tap-able card (photo page -> fullscreen via browser)
        val photoCards = capped.mapIndexed { i, p ->
            newMovieSearchResponse("Photo ${i + 1}", p, TvType.NSFW) {
                this.posterUrl = p
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, PHOTO_DATA) {
            this.posterUrl = poster
            this.backgroundPosterUrl = capped.firstOrNull()
            this.plot = "Photo album - ${capped.size} photos. Tap a photo, then use the open-in-browser icon on its page for fullscreen."
            this.tags = listOf("Photo Album", "${capped.size} photos")
            val recs = photoCards + relatedClean
            if (recs.isNotEmpty()) this.recommendations = recs
        }
    }

    // ---------- PHOTO LISTING (/photos/ and /video-photos/<video>/) ----------
    private suspend fun loadPhotoListing(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: "Photo Albums"

        val albums = doc.select("article.mms-photo-card")
            .filterNot { isAdCard(it) }
            .mapNotNull { toPhotoAlbumResult(it) }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, PHOTO_DATA) {
            this.posterUrl = albums.firstOrNull()?.posterUrl
            this.plot = "Photo albums (${albums.size}). Open one to browse all its photos."
            this.tags = listOf("Photo Albums")
            if (albums.isNotEmpty()) this.recommendations = albums
        }
    }

    // ---------- ALBUM (actor / playlist) ----------
    private suspend fun loadAlbum(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1.page-title, h1")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".actor-title")?.text()
            ?: "Album"

        // use first video card thumb as album art (never the site logo)
        val poster = imgSrc(doc.selectFirst("article .post-thumbnail-container img"))
            ?: imgSrc(doc.selectFirst("article img"))
        val plot = doc.selectFirst(".page-description, .entry-content, .term-description")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        fun collectVideos(d: org.jsoup.nodes.Document) {
            d.select("article.video-preview-item")
                .filterNot { isAdCard(it) }
                .forEach { el ->
                    val a = el.selectFirst("a[href]") ?: return@forEach
                    val link = normUrl(a.attr("href")) ?: return@forEach
                    val name = el.selectFirst(".title")?.text()?.takeIf { it.isNotBlank() }
                        ?: a.attr("title").takeIf { it.isNotBlank() }
                        ?: "Video ${episodes.size + 1}"
                    episodes.add(
                        newEpisode(link) {
                            this.name = name
                            this.episode = episodes.size + 1
                        }
                    )
                }
        }
        collectVideos(doc)

        // follow album pagination if the theme exposes it (max ALBUM_PAGES total pages)
        val slug = url.trimEnd('/').substringAfterLast("/actor/")
        for (p in 2..ALBUM_PAGES) {
            if (episodes.size == 0) break
            try {
                val nd = app.get("$mainUrl/actor/$slug/page/$p/", headers = headers).document
                val before = episodes.size
                collectVideos(nd)
                if (episodes.size == before) break
            } catch (e: Exception) {
                break
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = listOf("Album")
        }
    }

    // ---------- SINGLE VIDEO ----------
    private suspend fun loadVideo(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val playerCfg = parseMvpConfig(doc)
        val title = doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?: "MmsVibeX Video"

        val poster = playerCfg?.poster
            ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val plot = doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")
        val duration = playerCfg?.durationHint?.toInt()

        val tags = doc.select("a[href*=\"/tag/\"]").map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)

        // --- reference stills shown on the site page (clean main thumb + clean frames) ---
        val refs = mutableListOf<String>()
        val mainStill = imgSrc(doc.selectFirst(".wpst-clean-thumb-main img"))
        if (mainStill != null) refs.add(mainStill)
        for (im in doc.select(".wpst-clean-thumb-strip img")) {
            val s = imgSrc(im)
            if (s != null && !refs.contains(s)) refs.add(s)
        }
        // safety net: any clean-frame URL present in the page
        if (refs.isEmpty()) {
            Regex("https://cdn\\.mmsvibex\\.fit/clean-(frames|thumbnails)/[^\"'\\s]+\\.jpg")
                .findAll(doc.html())
                .forEach { m ->
                    val u = m.value
                    if (!refs.contains(u)) refs.add(u)
                }
        }
        val refImages = refs.take(8)
        val background = refImages.firstOrNull()

        // reference stills as big tap-able cards (photo page -> fullscreen via browser)
        val stillCards = refImages.mapIndexed { i, u ->
            newMovieSearchResponse("Reference ${i + 1}", u, TvType.NSFW) {
                this.posterUrl = u
            }
        }

        // --- dedicated photo albums for this video (recommendation cards -> gallery view) ---
        val photoRecs = mutableListOf<SearchResponse>()
        for (a in doc.select("a.mms-photo-link-card")) {
            val link = normUrl(a.attr("href")) ?: continue
            if (!link.contains("/photos/")) continue
            if (photoRecs.any { sameUrl(it.url, link) }) continue
            val albumTitle = a.selectFirst("strong")?.text()?.takeIf { it.isNotBlank() }
                ?: a.attr("aria-label").takeIf { it.isNotBlank() }
                ?: "Photo album"
            var countText: String? = null
            for (s in a.select("span")) {
                val t = s.text()
                if (Regex("\\d+").containsMatchIn(t)) countText = t
            }
            var name = "Photo Album: $albumTitle"
            if (countText != null) {
                val digits = Regex("(\\d+)").find(countText)?.groupValues?.get(1)
                if (digits != null) name = "$name ($digits photos)"
            }
            val cover = imgSrc(a.selectFirst("img"))
            photoRecs.add(
                newMovieSearchResponse(name, link, TvType.NSFW) {
                    this.posterUrl = cover
                }
            )
        }
        // "Show all N photo albums" -> /video-photos/<video>/ listing page
        val showAll = doc.selectFirst("a.mms-video-photo-show-all")
        if (showAll != null) {
            val link = normUrl(showAll.attr("href"))
            if (link != null && link.contains("/video-photos/") &&
                photoRecs.none { sameUrl(it.url, link) }
            ) {
                photoRecs.add(
                    newMovieSearchResponse("All Photo Albums From This Video", link, TvType.NSFW) {
                        this.posterUrl = background
                    }
                )
            }
        }

        // --- album this video belongs to: sibling videos under the info ---
        val albumRecs = mutableListOf<SearchResponse>()
        // in-page album preview cards first (zero extra requests)
        for (el in doc.select(".mms-video-album-preview article.video-preview-item")) {
            if (isAdCard(el)) continue
            val r = toVideoResult(el) ?: continue
            if (sameUrl(normUrl(r.url), url)) continue
            if (albumRecs.none { sameUrl(it.url, r.url) }) albumRecs.add(r)
        }
        // then fetch the FULL album (all sibling videos, user requirement)
        var albumUrl: String? = null
        val albumLinks = doc.select(".mms-video-album-preview a[href*=\"/actor/\"], a.mms-video-album-more a[href*=\"/actor/\"]")
        val firstAlbumLink = if (albumLinks.isEmpty()) null else albumLinks.first()
        if (firstAlbumLink != null) {
            albumUrl = normUrl(firstAlbumLink.attr("href"))
        }
        if (albumUrl != null && albumUrl.contains("/actor/")) {
            val full = fetchAlbumVideos(albumUrl, url)
            for (r in full) {
                if (albumRecs.none { sameUrl(it.url, r.url) }) albumRecs.add(r)
            }
        }
        // no album -> fall back to related videos so the info page is never empty
        if (albumRecs.isEmpty()) {
            for (el in doc.select("div.under-video-block article")) {
                if (isAdCard(el)) continue
                val r = toVideoResult(el) ?: continue
                if (sameUrl(normUrl(r.url), url)) continue
                if (albumRecs.none { sameUrl(it.url, r.url) }) albumRecs.add(r)
            }
        }

        val recommendations = (stillCards + photoRecs + albumRecs)
            .distinctBy { it.url }
            .take(MAX_RECS)

        // direct sources (ad-free): every source on the player page
        val sources = extractSources(doc, url)
        val data = if (sources.isEmpty()) url else sources.joinToString("||") { it.first }

        return newMovieLoadResponse(title, url, TvType.NSFW, data) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plot
            this.tags = tags.ifEmpty { listOf("MmsVibeX") }
            this.duration = duration
            if (recommendations.isNotEmpty()) this.recommendations = recommendations
        }
    }

    /** all sibling videos of an album (follows album pagination), for recommendations */
    private suspend fun fetchAlbumVideos(actorUrl: String, excludeUrl: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val slug = actorUrl.trimEnd('/').substringAfterLast("/actor/")
        if (slug.isBlank()) return out
        for (page in 1..ALBUM_PAGES) {
            val u = if (page == 1) "$mainUrl/actor/$slug/" else "$mainUrl/actor/$slug/page/$page/"
            val d = try {
                app.get(u, headers = headers).document
            } catch (e: Exception) {
                break
            }
            val before = out.size
            for (el in d.select("article.video-preview-item")) {
                if (isAdCard(el)) continue
                val r = toVideoResult(el) ?: continue
                if (sameUrl(normUrl(r.url), excludeUrl)) continue
                if (out.none { sameUrl(it.url, r.url) }) out.add(r)
            }
            if (out.size == before) break
            if (out.size >= MAX_RECS) break
        }
        return out
    }

    // ---------------- player config ----------------
    private data class MvpConfig(
        val poster: String? = null,
        val durationHint: Long? = null,
        val sources: List<MvpSource>? = null
    )

    private data class MvpSource(
        val url: String? = null,
        val mimeType: String? = null,
        val quality: Int? = null,
        val label: String? = null
    )

    private fun parseMvpConfig(doc: org.jsoup.nodes.Document): MvpConfig? {
        val script = doc.selectFirst("script.mvp-config")?.data()
            ?: doc.html().let { html ->
                Regex("class=\"mvp-config\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                    .find(html)?.groupValues?.get(1)
            }
            ?: return null
        return try {
            AppUtils.parseJson<MvpConfig>(script)
        } catch (e: Exception) {
            null
        }
    }

    private fun isDirectMedia(u: String): Boolean {
        val l = u.lowercase().substringBefore("?")
        return l.endsWith(".mp4") || l.endsWith(".m3u8") || l.endsWith(".mkv") ||
            l.endsWith(".webm") || l.endsWith(".m4v") || l.endsWith(".mov") ||
            l.contains("/processed/") || l.contains("/trailers/")
    }

    /** All playable sources found on a video page, with labels */
    private fun extractSources(doc: org.jsoup.nodes.Document, pageUrl: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()

        // 1) mvp-config JSON sources (official player config)
        parseMvpConfig(doc)?.sources?.forEach { s ->
            val u = s.url ?: return@forEach
            if (u.isBlank()) return@forEach
            val label = when {
                !s.label.isNullOrBlank() && (s.quality ?: 0) > 0 -> "${s.label} - ${s.quality}p"
                !s.label.isNullOrBlank() -> s.label
                (s.quality ?: 0) > 0 -> "${s.quality}p"
                else -> "Original"
            }
            out.add(u to label)
        }

        // 2) <video><source> tags
        if (out.isEmpty()) {
            doc.select("video source[src]").forEach {
                val u = it.attr("src")
                if (u.isNotBlank()) out.add(u to (it.attr("title").ifBlank { "Original" }))
            }
        }

        // 3) raw scan for cdn mp4 (last resort)
        if (out.isEmpty()) {
            Regex("https://cdn\\.mmsvibex\\.fit/[^\"'\\\\\\s]+\\.mp4[^\"'\\\\\\s]*")
                .findAll(doc.html())
                .forEach { out.add(it.value to "Original") }
        }

        return out.distinctBy { it.first }
    }

    // ---------------- LINKS ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank() || data == PHOTO_DATA) return false

        var found = false
        val candidates = if (data.contains("||")) data.split("||") else listOf(data)

        for (c in candidates.map { it.trim() }.filter { it.isNotBlank() }) {
            val pairs: List<Pair<String, String>> = if (isDirectMedia(c)) {
                listOf(c to "Original")
            } else {
                try {
                    val doc = app.get(c, headers = headers).document
                    extractSources(doc, c)
                } catch (e: Exception) {
                    emptyList()
                }
            }

            for ((linkUrl, label) in pairs) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$name - $label",
                        url = linkUrl
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = "$mainUrl/"
                        this.headers = mapOf(
                            "User-Agent" to UA,
                            "Referer" to "$mainUrl/"
                        )
                    }
                )
                found = true
            }
        }
        return found
    }

    // ---------------- REST API DTOs ----------------
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class WpPost(
        val id: Long? = null,
        val date: String? = null,
        val slug: String? = null,
        val link: String? = null,
        val title: WpTitle? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("_embedded")
        val embedded: WpEmbedded? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class WpTitle(val rendered: String? = null)

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class WpEmbedded(
        @com.fasterxml.jackson.annotation.JsonProperty("wp:featuredmedia")
        val featuredMedia: List<WpMedia>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class WpMedia(
        @com.fasterxml.jackson.annotation.JsonProperty("source_url")
        val sourceUrl: String? = null
    )
}
