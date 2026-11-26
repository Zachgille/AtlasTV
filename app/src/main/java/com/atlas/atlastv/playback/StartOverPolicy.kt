package com.atlas.atlastv.playback

/** Completed titles already Play from zero; peeks can restart without waiting for Resume eligibility. */
fun canStartOver(record: WatchProgressRecord?, type: WatchProgressContentType, id: Int, seriesId: Int? = null): Boolean =
    record != null && record.contentType == type && record.streamId == id && !record.completed && record.positionMs > 0 &&
        (type != WatchProgressContentType.SeriesEpisode || record.seriesId == null || record.seriesId == seriesId)
