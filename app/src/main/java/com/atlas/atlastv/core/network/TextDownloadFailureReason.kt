package com.atlas.atlastv.core.network

enum class TextDownloadFailureReason {
    InvalidUrl,
    DnsFailure,
    CleartextBlocked,
    ConnectionRefused,
    TlsFailure,
    NetworkFailure,
    Timeout,
    HttpStatus,
    EmptyResponse,
}
