// use an integer for version numbers
version = 5

android {
    namespace = "com.redowan"
}

cloudstream {
    description = """
        Krx18 (krx18.com) - erotic movies 18+ site. Ad-free where possible.
        All genres (countries + tags), paginated search, latest movies.
        Server 1 (9stream HLS) fully supported. Server 2 (abyss) is browser-locked and cannot play in CloudStream.
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

    iconUrl = "https://krx18.com/wp-content/uploads/2022/10/cropped-krx18A-192x192.png"
}
