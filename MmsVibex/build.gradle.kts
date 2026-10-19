// use an integer for version numbers
version = 5

android {
    namespace = "com.redowan"
}

cloudstream {
    description = """
        MmsVibeX - desi viral mms leaks site. Ad-free direct streams.
        Reference stills, photo galleries and album videos are big tap-able cards under the info.
        Every photo opens a detail page with the full-size image; use its open-in-browser
        icon to view the photo in true fullscreen.
        All categories, albums (playlists), search with pages and home sorting (latest / popular / most viewed / longest / random).
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

    iconUrl = "https://cdn.mmsvibex.fit/mmsvibex_logo.png"
}
