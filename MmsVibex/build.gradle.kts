// use an integer for version numbers
version = 2

android {
    namespace = "com.redowan"
}

cloudstream {
    description = """
        MmsVibeX - desi viral mms leaks site. Ad-free direct streams.
        All categories, albums (playlists), search with pages and home sorting (latest / popular / most viewed / longest / random).
    """.trimIndent()
    authors = listOf("Redowan", "khadimsorder2-hue")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    tvTypes = listOf(
        "NSFW",
        "Movie",
        "TvSeries"
    )
    language = "bn"

    iconUrl = "https://cdn.mmsvibex.fit/mmsvibex_logo.png"
}
