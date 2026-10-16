// use an integer for version numbers
version = 2

android {
    namespace = "com.redowan"
}

cloudstream {
    description = """
        Krx18 (krx18.com) - erotic movies 18+ site. Ad-free where possible.
        All genres (countries + tags), paginated search, latest movies.
        Server 1 (9stream HLS) fully decoded + supported.
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
        "Movie"
    )
    language = "bn"

    iconUrl = "https://krx18.com/wp-content/uploads/2022/10/cropped-krx18A-192x192.png"
}
