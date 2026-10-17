// use an integer for version numbers
version = 1

android {
    namespace = "com.redowan"
}

cloudstream {
    description = "MLSBD (mlsbd.co) — Bengali, Bollywood, Hollywood movies & Hoichoi/Chorki/Addatimes web series. Direct download links via GDFlix R2 CDN + MultiCloud watch streams."
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
        "TvSeries",
        "Others",
    )
    language = "bn"

    iconUrl = "https://mlsbd.co/wp-content/uploads/2020/09/mlsbd-icon.png"
}
