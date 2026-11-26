package com.atlas.atlastv.xtream.model

data class XtreamAccountInfo(
    val auth: Int?,
    val status: String?,
    val message: String?,
    val activeConnections: Int?,
    val maxConnections: Int?,
    val expirationDate: String?,
    val serverInfo: XtreamServerInfo?,
)
