package com.atlas.atlastv.core.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class OkHttpTextDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build(),
) : TextDownloader {
    override suspend fun download(url: String): TextDownloadResult =
        withContext(Dispatchers.IO) {
            val request = try {
                Request.Builder()
                    .url(url)
                    .get()
                    .build()
            } catch (_: IllegalArgumentException) {
                return@withContext TextDownloadResult.Failure(
                    reason = TextDownloadFailureReason.InvalidUrl,
                    message = NetworkFailureMapper.messageFor(TextDownloadFailureReason.InvalidUrl),
                )
            }

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext TextDownloadResult.Failure(
                            reason = TextDownloadFailureReason.HttpStatus,
                            message = "Request returned HTTP ${response.code}.",
                            httpStatus = response.code,
                            responseByteCount = response.body.contentLength().takeIf { it >= 0 },
                        )
                    }

                    val body = response.body.string()
                    val byteCount = response.body.contentLength().takeIf { it >= 0 }
                        ?: body.toByteArray(Charsets.UTF_8).size.toLong()
                    if (body.isBlank()) {
                        return@withContext TextDownloadResult.Failure(
                            reason = TextDownloadFailureReason.EmptyResponse,
                            message = NetworkFailureMapper.messageFor(TextDownloadFailureReason.EmptyResponse),
                            responseByteCount = byteCount,
                        )
                    }

                    TextDownloadResult.Success(
                        text = body,
                        byteCount = byteCount,
                    )
                }
            } catch (exception: Exception) {
                val reason = NetworkFailureMapper.fromThrowable(exception)
                TextDownloadResult.Failure(
                    reason = reason,
                    message = NetworkFailureMapper.messageFor(reason),
                )
            }
        }
}
