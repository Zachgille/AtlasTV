package com.atlas.atlastv.data.catalog

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One process owns this catalog. Provider edits wait for its in-flight refresh to finish. */
class CatalogRefreshGate {
    private val mutex = Mutex()
    suspend fun <T : Any> refreshIfIdle(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        try { return block() } finally { mutex.unlock() }
    }
    suspend fun <T> editProvider(block: suspend () -> T): T = mutex.withLock { block() }
    companion object { val Shared = CatalogRefreshGate() }
}
