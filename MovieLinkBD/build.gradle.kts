// use an integer for version numbers
version = 5

android {
    namespace = "com.redowan"
}

cloudstream {
    description = "MovieLinkBD — dual platform (movielinkbd.one mirrors + movielinkbd.is): getLink/getWatch + vircloud-signed quality links, CNCVerse-grade extractors, per-source speed + view counters."
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
