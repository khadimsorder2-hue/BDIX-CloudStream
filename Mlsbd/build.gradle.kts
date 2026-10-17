// use an integer for version numbers
version = 3

android {
    namespace = "com.redowan"
}

cloudstream {
    description = "MLSBD (mlsbd.co) — Latest, Bengali, Bangla Dubbed, Bollywood & Hollywood movies/series. Cloudflare-safe fetching with one-tap WebView verify, savelinks resolver + GDFlix R2 / GDxFiles / MultiCloud / HubCloud / Pixeldrain direct streams."
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
        "AsianDrama",
        "AnimeMovie",
    )
    language = "bn"

    iconUrl = "https://mlsbd.co/wp-content/uploads/2020/09/mlsbd-icon.png"
}
