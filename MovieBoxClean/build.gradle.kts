// use an integer for version numbers
version = 1

android {
    namespace = "com.redowan"
}

cloudstream {
    description = "MovieBox Clean — the same full aoneroom API client as MovieBox (Trending / Movies / TV Series / Anime / Live Sports & Events, Hindi + Bengali dubbed rows, per-language audio sources, built-in subtitles, direct signed CDN streams) but with obvious 18+ / adult titles filtered out of every row and search. Install this one for family-safe browsing."
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
        "Anime",
        "AnimeMovie",
    )
    language = "en"

    iconUrl = "https://raw.githubusercontent.com/RVRBEAST76/allforu-repo/main/moviebox.png"
}
