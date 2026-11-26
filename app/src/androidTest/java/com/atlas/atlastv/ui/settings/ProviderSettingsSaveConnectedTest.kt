package com.atlas.atlastv.ui.settings

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.data.catalog.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Normal Settings UI; every account/file is a unique cache fixture. No real provider calls. */
class ProviderSettingsSaveConnectedTest {
    @get:Rule val ui = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val folder = File(instrumentation.targetContext.cacheDir,"settings-save-${System.nanoTime()}").apply { mkdirs() }
    private val file = File(folder,"settings.json")
    private val account = ProviderSettingsSnapshot("http://example.invalid","fixture-user","fixture-password")
    private val status = ProviderSyncStatusSnapshot(status = CatalogSyncStatus.Success,lastSuccessfulSyncAtMs = 100,moviesImported = 12,seriesImported = 9)
    private val gate = CountDownLatch(1)
    private val writes = AtomicInteger()
    private val syncs = AtomicInteger()
    @Volatile private var block = false
    @Volatile private var fail = false
    private val store = ProviderSettingsStore(file) { temporary,target ->
        if (target == file) {
            writes.incrementAndGet()
            if (block) check(gate.await(10,TimeUnit.SECONDS))
            if (fail) throw IOException("fixture-password")
        }
        atomicProviderReplace(temporary,target)
    }
    private lateinit var holder: ProviderSettingsStateHolder
    private var settingsOpen by mutableStateOf(true)
    private fun start(seed: Boolean = true, providerPage: Boolean = true) {
        if (seed) { assertTrue(store.saveSettings(account)); assertTrue(store.saveSyncStatus(status)) }
        holder = ProviderSettingsStateHolder(store,scope,{ ProviderConnectionResult(true) }, { _,stage ->
            syncs.incrementAndGet(); stage(com.atlas.atlastv.xtream.network.XtreamLoadStage.VodStreams)
            CatalogImportResult(CatalogSyncStatus.Success,100,200,15,10,0,0)
        },bootstrapProviderSettings = account)
        ui.setContent { com.atlas.atlastv.ui.theme.AtlasTvTheme {
            BackHandler { settingsOpen = false }
            if (settingsOpen) SettingsScreen(holder,{ settingsOpen = false },{})
            else androidx.tv.material3.Text("Library available")
        } }
        ui.onNodeWithTag("settings_back").assertIsFocused()
        if (providerPage) ui.onNodeWithTag("settings_section_provider").performClick()
    }
    private fun draft() = ui.runOnIdle { holder.updateBaseUrl(account.baseUrl); holder.updateUsername(account.username); holder.updatePassword("new-password") }
    private fun focus(tag: String) { ui.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus); ui.onNodeWithTag(tag).assertIsFocused() }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); ui.waitForIdle() }
    private fun waitSaved() { ui.waitUntil(10_000) { !holder.state.providerSaving } }

    @Test fun failedSaveKeepsOldAccountAndNativeRetryAcknowledgesTheDraftWithoutMovingFocus() {
        start(); val bytes = file.readBytes(); draft(); fail = true
        focus("provider_save_action"); key(KeyEvent.KEYCODE_DPAD_CENTER); waitSaved()
        assertTrue(holder.state.providerSaveFailed); assertArrayEquals(bytes,file.readBytes())
        assertEquals(account,store.load().settings); assertEquals(status,store.load().syncStatus)
        ui.onNodeWithTag("provider_save_action").assertTextEquals("Retry save").assertIsFocused()
        ui.onNodeWithTag("provider_action_message").assertTextEquals("Couldn’t save provider details. Your saved account is unchanged.")
        val bounds = ui.onNodeWithTag("provider_action_message").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.bottom <= ui.onRoot().fetchSemanticsNode().boundsInRoot.bottom)
        ui.onNodeWithTag("provider_action_message").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val layouts = mutableListOf<TextLayoutResult>(); assertTrue(action(layouts))
            assertFalse(layouts.single().hasVisualOverflow)
            assertTrue(layouts.single().size.height <= bounds.height+1)
        }
        instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(300)
        val pixels = ui.onNodeWithTag("provider_save_action").captureToImage().toPixelMap()
        assertTrue("Focused Retry must paint the visible accent border", (1..5).any { x ->
            val color = pixels[x,pixels.height/2]; color.blue > 0.8f && color.red < 0.5f && color.green < 0.7f
        })
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(instrumentation.targetContext.cacheDir,"settings-save-refined.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        }
        fail = false; key(KeyEvent.KEYCODE_DPAD_CENTER); waitSaved()
        assertFalse(holder.state.providerSaveFailed); assertEquals("new-password",store.load().settings.password)
        assertEquals(status,store.load().syncStatus); assertEquals("",holder.state.passwordInput)
        ui.onNodeWithTag("provider_save_action").assertIsFocused()
        assertEquals(0,syncs.get())
    }

    @Test fun slowSaveSuppressesRepeatsAndBackRemainsUsableWithoutAcknowledgementFocusTheft() {
        start(); draft(); val original = file.readBytes(); block = true; val before = writes.get()
        focus("provider_save_action"); key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.waitUntil(5_000) { writes.get() == before+1 }
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_CENTER) }
        assertEquals(before+1,writes.get()); assertTrue(holder.state.providerSaving)
        assertArrayEquals(original,file.readBytes())
        assertEquals(account,holder.savedCredentialsOrNull()?.toProviderSettingsSnapshot())
        key(KeyEvent.KEYCODE_BACK); ui.onNodeWithText("Library available").assertExists()
        gate.countDown(); waitSaved(); assertEquals("new-password",store.load().settings.password)
        ui.onNodeWithText("Library available").assertExists()
    }

    @Test fun failedRefreshStatusSaveOffersNativeRetryWithoutRepeatingRefreshOrChangingAccount() {
        start(providerPage = false); fail = true; focus("provider_sync_action"); key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.waitUntil(10_000) { holder.state.syncStatus.status == CatalogSyncStatus.Success && holder.state.syncStatus.moviesImported == 15 }
        assertTrue(holder.state.syncStatusSaveFailed); assertEquals(status,store.load().syncStatus); assertEquals(account,store.load().settings)
        ui.onNodeWithTag("provider_sync_status_label").assertTextEquals("Couldn’t save refresh status. Your library is still available.")
        ui.onNodeWithTag("provider_sync_action").assertTextEquals("Retry save")
        fail = false; focus("provider_sync_action"); key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.waitUntil(10_000) { !holder.state.syncStatusSaveFailed }
        assertEquals(1,syncs.get()); assertEquals(15,ProviderSettingsStore(file).load().syncStatus.moviesImported)
        assertEquals(account,store.load().settings); ui.onNodeWithTag("provider_sync_action").assertIsFocused()
    }

    @Test fun unreadableInitialSettingsAreNotBootstrappedOverAndNativeReadRetryRecovers() {
        file.writeText("{broken"); start(seed = false)
        assertTrue(holder.state.providerReadFailed); assertEquals("{broken",file.readText())
        ui.onNodeWithTag("provider_save_action").assertTextEquals("Retry load")
        assertTrue(store.saveSettings(account)); focus("provider_save_action"); key(KeyEvent.KEYCODE_DPAD_CENTER); waitSaved()
        assertTrue(holder.state.hasSavedCredentials); assertFalse(holder.state.providerReadFailed)
        assertEquals("",holder.state.baseUrlInput); assertEquals("",holder.state.usernameInput); assertEquals("",holder.state.passwordInput)
        ui.onNodeWithTag("provider_save_action").assertIsFocused(); assertEquals(0,syncs.get())
    }

    @Test fun freshNamespaceReadsDiskRecoveryAndRefreshRepairsPrimaryWithoutExposingSavedCredentials() {
        val other = File(folder,"source.json"); val source = ProviderSettingsStore(other)
        source.saveSettings(account); source.saveSyncStatus(status)
        File(folder,"settings.json.lastgood").writeBytes(File(folder,"source.json.lastgood").readBytes())
        file.writeText("{broken"); start(seed = false)
        assertTrue(holder.state.hasSavedCredentials); assertFalse(holder.state.providerReadFailed)
        ui.onNodeWithTag("provider_action_message").assertTextEquals("Recovered your saved provider details.")
        assertEquals("",holder.state.passwordInput)
        ui.onNodeWithTag("settings_section_library").performClick(); focus("provider_sync_action"); key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.waitUntil(10_000) { holder.state.syncStatus.moviesImported == 15 }
        assertEquals(ProviderSettingsReadIssue.None,store.readIssue)
        assertEquals(account,ProviderSettingsStore(file).load().settings); assertEquals(15,store.load().syncStatus.moviesImported)
    }
    @After fun close() { gate.countDown(); scope.cancel(); folder.deleteRecursively() }
}
