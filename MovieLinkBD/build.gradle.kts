// use an integer for version numbers
version = 3

android {
    namespace = "com.redowan"
}

cloudstream {
    description = "MovieLinkBD (movielinkbd.li) — Recently Updated, Bangla, Bangla Dubbed & Hindi movies/series. Auto-resolves the live domain, one-tap Cloudflare verify, inline player streams + HubCloud/GDFlix/Pixeldrain direct links."
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
