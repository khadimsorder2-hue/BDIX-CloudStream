// use an integer for version numbers
version = 1

android {
    namespace = "com.redowan"
}

cloudstream {
    description = "MovieBox — full aoneroom API client (AllForU v86 architecture): Trending / Movies / TV Series / Anime / Live Sports & Events, Hindi + Bengali dubbed rows, per-language audio sources (Hindi dub, Bengali dub…), built-in subtitles, trap-video bypass with direct signed CDN streams + per-source speed & view counters. Contains all content incl. 18+ — use MovieBox Clean to hide adult titles."
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
