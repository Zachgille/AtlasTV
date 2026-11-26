package com.atlas.atlastv.xtream.network

import com.atlas.atlastv.xtream.model.XtreamAccountInfo
import com.atlas.atlastv.xtream.model.XtreamCategory
import com.atlas.atlastv.xtream.model.XtreamLiveStream
import com.atlas.atlastv.xtream.model.XtreamSeries
import com.atlas.atlastv.xtream.model.XtreamVodStream

sealed interface XtreamApiResult {
    data class Success(
        val accountInfo: XtreamAccountInfo,
        val liveCategories: List<XtreamCategory>,
        val liveStreams: List<XtreamLiveStream>,
        val vodCategories: List<XtreamCategory>,
        val vodStreams: List<XtreamVodStream>,
        val seriesCategories: List<XtreamCategory>,
        val series: List<XtreamSeries>,
        val timings: XtreamCatalogTimings = XtreamCatalogTimings(),
    ) : XtreamApiResult

    data class Failure(
        val message: String,
        val redactedUrl: String?,
        val endpointLabel: String?,
        val category: XtreamFailureCategory,
        val httpStatus: Int? = null,
        val responseByteCount: Long? = null,
        val timings: XtreamCatalogTimings = XtreamCatalogTimings(),
    ) : XtreamApiResult
}
