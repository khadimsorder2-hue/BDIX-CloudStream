// use an integer for version numbers
version = 1

android {
    namespace = "com.redowan"
}

cloudstream {
    description = "Short dramas with full episodes from ReelShort, DramaBox, GoodShort, ShortMax, NetShort and more. Watch free, no app needed."
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
        "TvSeries",
    )
    language = "en"

    iconUrl = "https://directdrama.com/icons/icon-192.png"
}
