package com.atlas.atlastv.core.network

interface TextDownloader {
    suspend fun download(url: String): TextDownloadResult
}
