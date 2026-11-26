package com.atlas.atlastv.xtream.network

enum class XtreamFailureCategory {
    InvalidConfig,
    Authentication,
    InvalidUrl,
    DnsFailure,
    CleartextBlocked,
    Timeout,
    ConnectionRefused,
    TlsFailure,
    Non2xxStatus,
    EmptyResponse,
    MalformedJson,
    MappingFailure,
    FilterExcludedAllItems,
    UnknownError,
}
