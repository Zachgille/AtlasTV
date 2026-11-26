package com.atlas.atlastv.core.network

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object NetworkFailureMapper {
    fun fromThrowable(throwable: Throwable): TextDownloadFailureReason =
        when {
            throwable is UnknownHostException -> TextDownloadFailureReason.DnsFailure
            throwable is SocketTimeoutException -> TextDownloadFailureReason.Timeout
            throwable is InterruptedIOException -> TextDownloadFailureReason.Timeout
            throwable is ConnectException && throwable.message.orEmpty().contains("refused", ignoreCase = true) ->
                TextDownloadFailureReason.ConnectionRefused
            throwable is SSLException -> TextDownloadFailureReason.TlsFailure
            throwable is SecurityException -> TextDownloadFailureReason.CleartextBlocked
            throwable.message.orEmpty().contains("CLEARTEXT", ignoreCase = true) ->
                TextDownloadFailureReason.CleartextBlocked
            else -> TextDownloadFailureReason.NetworkFailure
        }

    fun messageFor(reason: TextDownloadFailureReason): String =
        when (reason) {
            TextDownloadFailureReason.InvalidUrl -> "Invalid URL."
            TextDownloadFailureReason.DnsFailure -> "DNS lookup failed."
            TextDownloadFailureReason.CleartextBlocked -> "Cleartext HTTP appears to be blocked."
            TextDownloadFailureReason.ConnectionRefused -> "Connection refused."
            TextDownloadFailureReason.TlsFailure -> "TLS/SSL request failed."
            TextDownloadFailureReason.NetworkFailure -> "Network request failed."
            TextDownloadFailureReason.Timeout -> "Network request timed out."
            TextDownloadFailureReason.HttpStatus -> "Request returned a non-2xx HTTP status."
            TextDownloadFailureReason.EmptyResponse -> "Response was empty."
        }
}
