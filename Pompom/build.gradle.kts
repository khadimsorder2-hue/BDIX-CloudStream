// use an integer for version numbers
version = 3

android {
    namespace = "com.redowan"
}

cloudstream {
    description = """
        Pompom - PomPomBD.Com (TeraBox webmaster page on teraboxpage.com).
        All categories (All / Desi / New Desi) with pages, folder shares opened as series,
        direct m3u8 streaming without login. Dead/deleted shares are auto-filtered from
        lists, playback auto-recovers with a fresh session + fresh stream sign. No account needed.
        Marked pure NSFW: hidden from normal movie/TV sections - enable NSFW in
        Settings > Providers > Preferred media type to see and use this provider.
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
        "NSFW"
    )
    language = "bn"

    iconUrl = "https://s5.teraboxcdn.com/common-static/favicon2.ico"
}
