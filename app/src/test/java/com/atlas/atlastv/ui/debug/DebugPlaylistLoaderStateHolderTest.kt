package com.atlas.atlastv.ui.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.atlas.atlastv.xtream.model.XtreamCredentials
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugPlaylistLoaderStateHolderTest {
    @Test
    fun configuredLocalDebugUrlIsAvailable() {
        val stateHolder = DebugPlaylistLoaderStateHolder(
            scope = CoroutineScope(Dispatchers.Unconfined),
            localDebugPlaylistUrl = "https://example.test/get.php?username=fake_user&password=fake_password",
        )

        assertTrue(stateHolder.hasLocalDebugPlaylistUrl)
    }

    @Test
    fun blankLocalDebugUrlIsUnavailable() {
        val stateHolder = DebugPlaylistLoaderStateHolder(
            scope = CoroutineScope(Dispatchers.Unconfined),
            localDebugPlaylistUrl = "",
        )

        assertFalse(stateHolder.hasLocalDebugPlaylistUrl)
    }

    @Test
    fun configuredLocalXtreamCredentialsAreAvailable() {
        val stateHolder = DebugPlaylistLoaderStateHolder(
            scope = CoroutineScope(Dispatchers.Unconfined),
            localXtreamCredentials = XtreamCredentials(
                baseUrl = "http://example.test",
                username = "fake_user",
                password = "fake_password",
            ),
        )

        assertTrue(stateHolder.hasLocalXtreamConfig)
    }

    @Test
    fun configuredStatusIsAvailableWhenFieldsArePresent() {
        val stateHolder = DebugPlaylistLoaderStateHolder(
            scope = CoroutineScope(Dispatchers.Unconfined),
            localXtreamCredentials = XtreamCredentials(
                baseUrl = "http://example.test",
                username = "fake_user",
                password = "fake_password",
            ),
        )

        assertEquals(true, stateHolder.hasLocalXtreamConfig)
    }

    @Test
    fun missingLocalXtreamCredentialsAreUnavailable() {
        val stateHolder = DebugPlaylistLoaderStateHolder(
            scope = CoroutineScope(Dispatchers.Unconfined),
            localXtreamCredentials = null,
        )

        assertFalse(stateHolder.hasLocalXtreamConfig)
    }
}
