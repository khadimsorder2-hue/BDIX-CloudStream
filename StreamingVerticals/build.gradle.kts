// use an integer for version numbers
version = 1

android {
    namespace = "com.redowan"
}

cloudstream {
    description = "Vertical mini-dramas & short dramas from ReelShort, DramaBox, ShortMax, GoodShort, NetShort and more. Full episodes and full-movie cuts streamed from official YouTube channels."
    authors = listOf("Redowan")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    // List of video source types. Users are able to filter for extensions in a given category.
    tvTypes = listOf(
        "Movie",
    )
    language = "en"

    iconUrl = "https://www.streamingverticals.com/assets/og-default.png"
}
