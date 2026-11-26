package com.atlas.atlastv.core.network

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkFailureMapperTest {
    @Test
    fun mapsUnknownHostToDnsFailure() {
        assertEquals(TextDownloadFailureReason.DnsFailure, NetworkFailureMapper.fromThrowable(UnknownHostException()))
    }

    @Test
    fun mapsSocketTimeoutToTimeout() {
        assertEquals(TextDownloadFailureReason.Timeout, NetworkFailureMapper.fromThrowable(SocketTimeoutException()))
    }

    @Test
    fun mapsCleartextExceptionToCleartextBlockedWhenDetectable() {
        val exception = IllegalStateException("CLEARTEXT communication not permitted")

        assertEquals(TextDownloadFailureReason.CleartextBlocked, NetworkFailureMapper.fromThrowable(exception))
    }
}
