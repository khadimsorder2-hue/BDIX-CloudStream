// use an integer for version numbers
version = 4

android {
    namespace = "com.redowan"
}

cloudstream {
    description = "MovieLinkBD (movielinkbd.is) — Latest, Bangla, Bangla Dubbed & Hindi movies/series. Direct vircloud-signed links per quality (720p/1080p/4K), WP-JSON search, MultiCloud/GDFlix/HubCloud extractors for legacy posts."
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

    iconUrl = "https://movieslinkbd.com/favicon.ico"
}
