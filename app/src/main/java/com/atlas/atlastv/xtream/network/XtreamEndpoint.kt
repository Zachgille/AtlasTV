package com.atlas.atlastv.xtream.network

enum class XtreamEndpoint(val action: String?) {
    AccountInfo(null),
    LiveCategories("get_live_categories"),
    LiveStreams("get_live_streams"),
    VodCategories("get_vod_categories"),
    VodStreams("get_vod_streams"),
    VodInfo("get_vod_info"),
    SeriesCategories("get_series_categories"),
    Series("get_series"),
    SeriesInfo("get_series_info"),
}
