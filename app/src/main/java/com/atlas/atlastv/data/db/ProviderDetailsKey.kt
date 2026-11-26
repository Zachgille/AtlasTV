package com.atlas.atlastv.data.db

import com.atlas.atlastv.xtream.model.XtreamCredentials
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Length prefixes keep opaque credential tuples distinct without persisting their values. */
fun providerDetailsCacheKey(credentials: XtreamCredentials, namespace: String): String {
    val hash = MessageDigest.getInstance("SHA-256")
    for (value in listOf(namespace, credentials.baseUrl.trim().trimEnd('/'), credentials.username, credentials.password)) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
    }
    // Keep the existing key bytes; Formatter allocation here is costly during card composition.
    val digest = hash.digest()
    val hex = CharArray(digest.size * 2)
    for (index in digest.indices) {
        val value = digest[index].toInt() and 0xff
        hex[index * 2] = HexDigits[value ushr 4]
        hex[index * 2 + 1] = HexDigits[value and 0x0f]
    }
    return String(hex)
}

private const val HexDigits = "0123456789abcdef"
