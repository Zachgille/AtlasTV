package com.atlas.atlastv.core.network

sealed interface TextDownloadResult {
    data class Success(
        val text: String,
        val byteCount: Long?,
    ) : TextDownloadResult

    data class Failure(
        val reason: TextDownloadFailureReason,
        val message: String,
        val httpStatus: Int? = null,
        val responseByteCount: Long? = null,
    ) : TextDownloadResult
}
