// use an integer for version numbers
version = 1

android {
    namespace = "com.redowan"
}

cloudstream {
    description = "MovieLinkBD (movieslinkbd.com family) — Bangla, Hindi, English, Dual Audio movies & web series with direct 480p-2160p MKV/MP4 CDN links. Auto-signed vircloud streams."
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

    iconUrl = "https://movieslinkbd.com/favicon.ico"
}
