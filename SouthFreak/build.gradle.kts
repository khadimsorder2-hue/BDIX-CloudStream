// use an integer for version numbers
version = 1

android {
    namespace = "com.redowan"
}

cloudstream {
    description = """
        SouthFreak (southfreak.vip) — Hindi dubbed, dual audio, Bengali, South Indian &
        Hollywood movies + WEB/TV series. All 44 site categories (languages, genres,
        IMDb 250, natok/telefilm, reality shows), REST search, post metadata
        (IMDb/genres/storyline) and download buttons resolved through TechZed link
        protector into direct GDFlix R2 CDN + MultiCloud streams.
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
        "Movie",
        "TvSeries",
        "Others",
    )
    language = "hi"

    iconUrl = "https://southfreak.vip/wp-content/uploads/2023/08/favicon.png"
}
