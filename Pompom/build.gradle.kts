// use an integer for version numbers
version = 1

android {
    namespace = "com.redowan"
}

cloudstream {
    description = """
        Pompom - PomPomBD.Com (TeraBox webmaster page on teraboxpage.com).
        All categories (All / Desi / New Desi) with pages, folder shares opened as series,
        direct m3u8 streaming without login. No account needed.
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

    iconUrl = "https://s5.teraboxcdn.com/common-static/favicon2.ico"
}
