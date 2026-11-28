package dev.lumenara.tv

/** Saved navigation identity is independent of the displayed label. */
internal enum class LibraryRoute(val id: String, val labelRes: Int, val legacyName: String, val kind: String = "") {
    Home("home", R.string.route_home, "Ana Sayfa"),
    Live("live", R.string.route_live, "Canlı TV", "LIVE"),
    Movies("movies", R.string.route_movies, "Filmler", "MOVIE"),
    Series("series", R.string.route_series, "Diziler", "SERIES"),
    Watchlist("watchlist", R.string.route_watchlist, "Listem"),
    Search("search", R.string.route_search, "Ara"),
    Settings("settings", R.string.route_settings, "Ayarlar"),
    Sources("sources", R.string.route_sources, "IPTV Kaynakları"),
    Restore("restore", R.string.route_restore, "Yedek geri yükle");

    companion object {
        val hub = listOf(Live, Movies, Series, Watchlist, Search, Settings)

        // Accept an older saved section and existing callers during the incremental migration.
        fun fromSaved(value: String): LibraryRoute =
            entries.firstOrNull { it.id == value || it.legacyName == value } ?: Home
    }
}
