package com.atlas.atlastv.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.atlas.atlastv.data.catalog.CatalogSyncStatus
import com.atlas.atlastv.ui.theme.AtlasAccent
import com.atlas.atlastv.ui.theme.AtlasBackground
import com.atlas.atlastv.ui.theme.AtlasMutedText
import com.atlas.atlastv.ui.theme.AtlasSurface
import com.atlas.atlastv.ui.theme.AtlasSurfaceFocused
import com.atlas.atlastv.ui.theme.AtlasText

@Composable
fun SettingsScreen(
    stateHolder: ProviderSettingsStateHolder,
    onBack: () -> Unit,
    onAdvancedDiagnostics: () -> Unit,
    onRefreshCatalog: (() -> Unit)? = null,
    playbackPreferences: com.atlas.atlastv.playback.PlaybackPreferencesStateHolder? = null,
    navigationState: SettingsNavigationState? = null,
) {
    val state = stateHolder.state
    val navigation = navigationState ?: remember { SettingsNavigationState() }
    val section = navigation.section
    val backRequester = remember { FocusRequester() }
    val sectionRequesters = remember { SettingsSection.entries.associateWith { FocusRequester() } }
    val entryRequesters = remember { SettingsSection.entries.associateWith { FocusRequester() } }
    val urlRequester = remember { FocusRequester() }
    val usernameRequester = remember { FocusRequester() }
    val passwordRequester = remember { FocusRequester() }
    val connectionRequester = remember { FocusRequester() }
    var enterSection by remember { mutableStateOf<SettingsSection?>(null) }
    var backupsOpen by remember { mutableStateOf(false) }
    var restoreAfterBackups by remember { mutableStateOf(false) }
    fun select(target: SettingsSection) { navigation.section = target; enterSection = target }
    fun contentFocus(target: SettingsSection) = Modifier.focusRequester(entryRequesters.getValue(target))
        .focusProperties { left = sectionRequesters.getValue(target); up = backRequester }
    BackHandler(onBack = onBack)
    if (backupsOpen) BackupServersDialog(stateHolder, onDismiss = { backupsOpen = false; restoreAfterBackups = true })
    LaunchedEffect(backupsOpen, restoreAfterBackups) {
        if (!backupsOpen && restoreAfterBackups) { withFrameNanos { }; connectionRequester.requestFocus(); restoreAfterBackups = false }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (navigation.returningFromDiagnostics && section == SettingsSection.Advanced) {
            navigation.returningFromDiagnostics = false
            entryRequesters.getValue(SettingsSection.Advanced).requestFocus()
        } else backRequester.requestFocus()
    }
    LaunchedEffect(section, enterSection) {
        if (enterSection == section) {
            withFrameNanos { }
            if (section == SettingsSection.Provider && !state.hasSavedCredentials && !state.providerReadFailed) urlRequester.requestFocus()
            else entryRequesters.getValue(section).requestFocus()
            enterSection = null
        }
    }
    Column(
        Modifier.fillMaxSize().background(AtlasBackground)
            .padding(horizontal = 48.dp, vertical = 24.dp)
            .testTag("settings_screen")
            .semantics { contentDescription = "Settings"; testTagsAsResourceId = true },
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsButton("Back", onBack, "settings_back", width = 118, height = 48,
                modifier = Modifier.focusRequester(backRequester).focusProperties { down = sectionRequesters.getValue(section) })
            Text("Settings", color = AtlasText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.width(168.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsSection.entries.forEachIndexed { index, target ->
                    SettingsButton(target.title, { select(target) }, target.tag, width = 168, height = 50,
                        selected = target == section,
                        modifier = Modifier.focusRequester(sectionRequesters.getValue(target))
                            .focusProperties {
                                up = SettingsSection.entries.getOrNull(index - 1)?.let { sectionRequesters.getValue(it) } ?: backRequester
                                down = SettingsSection.entries.getOrNull(index + 1)?.let { sectionRequesters.getValue(it) } ?: FocusRequester.Cancel
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) { select(target); true } else false
                            })
                }
            }
            Column(Modifier.weight(1f).testTag("settings_content"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(section.title, color = AtlasText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("settings_section_title"))
                when (section) {
                    SettingsSection.Library -> {
                        ProviderStatusPanel(state, showProvider = false)
                        SettingsButton(when {
                            !state.hasSavedCredentials -> if (state.providerReadFailed) "Recover provider" else "Set up provider"
                            state.isSyncing -> "Refreshing…"
                            state.syncStatusSaveFailed -> "Retry save"
                            else -> "Refresh Library"
                        }, {
                            if (!state.hasSavedCredentials) select(SettingsSection.Provider)
                            else if (!state.isSyncing && state.syncStatusSaveFailed) stateHolder.retrySyncStatusSave()
                            else if (!state.isSyncing) { onRefreshCatalog?.invoke() ?: stateHolder.syncMoviesAndShowsAsync() }
                        }, "provider_sync_action", width = 400, height = 52, modifier = contentFocus(section))
                        if (state.providerSaveFailed || state.providerReadFailed) {
                            Text("Open Provider to recover your saved details.", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    SettingsSection.Playback -> {
                        Text("Start the next episode after the current one ends.", color = AtlasMutedText, style = MaterialTheme.typography.bodyLarge)
                        playbackPreferences?.let {
                            SettingsButton(it.state.actionLabel, it::toggleOrRetry, "settings_autoplay", width = 460, height = 58,
                                modifier = contentFocus(section))
                        } ?: SettingsButton("Playback preferences unavailable", {}, "settings_playback_unavailable",
                            width = 460, height = 58, modifier = contentFocus(section))
                    }
                    SettingsSection.Provider -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Saved details stay private.", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
                                ProviderTextField("Base URL", state.baseUrlInput, stateHolder::updateBaseUrl, "provider_base_url_field", imeAction = ImeAction.Next,
                                    modifier = Modifier.focusRequester(urlRequester).focusProperties { up = backRequester; down = usernameRequester })
                                ProviderTextField("Username", state.usernameInput, stateHolder::updateUsername, "provider_username_field", imeAction = ImeAction.Next,
                                    modifier = Modifier.focusRequester(usernameRequester).focusProperties { up = urlRequester; down = passwordRequester })
                                ProviderTextField("Password", state.passwordInput, stateHolder::updatePassword, "provider_password_field", isPassword = true, imeAction = ImeAction.Done,
                                    modifier = Modifier.focusRequester(passwordRequester).focusProperties { up = usernameRequester; right = connectionRequester })
                            }
                            Column(Modifier.weight(1f).testTag("provider_actions_panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SettingsButton(when { state.providerSaving -> "Saving…"; state.providerSaveFailed -> "Retry save"; state.providerReadFailed -> "Retry load"; else -> "Save provider" },
                                    stateHolder::saveProviderSettings, "provider_save_action", width = 400, height = 48,
                                    modifier = contentFocus(section).focusProperties { left = urlRequester; down = connectionRequester })
                                SettingsButton(if (state.syncStatusSaveFailed) "Retry save" else "Test connection",
                                    if (state.syncStatusSaveFailed) stateHolder::retrySyncStatusSave else stateHolder::testConnectionAsync,
                                    "provider_test_connection_action", width = 400, height = 48,
                                    modifier = Modifier.focusRequester(connectionRequester).focusProperties { left = sectionRequesters.getValue(section) })
                                if (state.hasSavedCredentials) SettingsButton("Backup servers", {
                                    stateHolder.beginBackupEditing(); backupsOpen = true
                                }, "provider_backups_open", width = 400, height = 48)
                                ProviderStatusPanel(state, showLibrary = false)
                            }
                        }
                    }
                    SettingsSection.Advanced -> {
                        Text("Troubleshooting tools.", color = AtlasMutedText, style = MaterialTheme.typography.bodyLarge)
                        SettingsButton("Open diagnostics", {
                            navigation.returningFromDiagnostics = true
                            onAdvancedDiagnostics()
                        }, "advanced_diagnostics_entry", width = 400, height = 52, modifier = contentFocus(section))
                        Text("Information courtesy of IMDb (https://www.imdb.com). Used with permission.",
                            color = AtlasMutedText, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("settings_discovery_attribution"))
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupServersDialog(holder: ProviderSettingsStateHolder, onDismiss: () -> Unit) {
    val state = holder.state
    val first = remember { FocusRequester() }; val second = remember { FocusRequester() }
    val save = remember { FocusRequester() }; val cancel = remember { FocusRequester() }
    var submitted by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(560.dp).heightIn(max = 440.dp).background(AtlasBackground, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .verticalScroll(rememberScrollState())
            .padding(24.dp).testTag("provider_backups_dialog"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Backup servers", color = AtlasText, style = MaterialTheme.typography.headlineSmall)
            Text("Use addresses for this same account.", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
            ProviderTextField("Backup URL 1", state.backupUrlOne, { holder.updateBackupUrl(0, it) }, "provider_backup_one",
                modifier = Modifier.focusRequester(first).focusProperties { down = second })
            ProviderTextField("Backup URL 2", state.backupUrlTwo, { holder.updateBackupUrl(1, it) }, "provider_backup_two", imeAction = ImeAction.Done,
                modifier = Modifier.focusRequester(second).focusProperties { up = first; down = save })
            (state.backupEditError ?: state.actionMessage?.takeIf { state.providerSaveFailed })?.let {
                Text(it, color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("provider_backup_error"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) { SettingsButton(if (state.providerSaving) "Saving…" else "Save", {
                    holder.saveBackupServers(); submitted = holder.state.providerSaving
                }, "provider_backups_save", modifier = Modifier.focusRequester(save).focusProperties { up = second; right = cancel }) }
                Box(Modifier.weight(1f)) { SettingsButton("Cancel", onDismiss, "provider_backups_cancel",
                    modifier = Modifier.focusRequester(cancel).focusProperties { up = second; left = save }) }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; first.requestFocus() }
        LaunchedEffect(state.providerSaving, state.providerSaveFailed, submitted) {
            if (submitted && !state.providerSaving && !state.providerSaveFailed) onDismiss()
        }
    }
}

@Composable
private fun ProviderStatusPanel(state: ProviderSettingsUiState, showLibrary: Boolean = true, showProvider: Boolean = true) {
    val status = state.syncStatus
    val accountIssue = state.actionMessage?.takeIf { showProvider && (state.providerSaveFailed || state.providerReadFailed) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AtlasSurface, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .border(BorderStroke(1.dp, AtlasSurfaceFocused), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag("provider_sync_status")
            .semantics { contentDescription = if (showLibrary) "Library status" else "Provider status" },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(if (accountIssue != null) accountIssue else if (state.syncStatusSaveFailed) "Couldn’t save refresh status. Your library is still available." else if (!showLibrary) { if (state.hasSavedCredentials) "Provider configured" else "No provider saved" } else when (status.status) {
            CatalogSyncStatus.Idle -> "Your library is ready to refresh."
            CatalogSyncStatus.Loading -> "Refreshing your library…"
            CatalogSyncStatus.Success -> "Library ready"
            CatalogSyncStatus.Failure -> status.refreshFailureMessage()
        }, color = AtlasText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(if (accountIssue != null) "provider_action_message" else "provider_sync_status_label"))
        if (showLibrary && status.status == CatalogSyncStatus.Loading) Text(status.currentStage ?: "Connecting…", color = AtlasMutedText,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("provider_sync_stage"))
        if (showLibrary && (status.moviesImported > 0 || status.seriesImported > 0)) Text("${status.moviesImported} movies · ${status.seriesImported} shows",
            color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("provider_sync_counts"))
        if (showLibrary) (status.lastSuccessfulSyncAtMs ?: status.lastCompletedAtMs?.takeIf { status.status == CatalogSyncStatus.Success })?.let {
            Text("Last refreshed ${it.safeAgeLabel(System.currentTimeMillis())}", color = AtlasMutedText,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("provider_sync_last_updated"))
        }
        if (showProvider && state.connectionStatus in setOf("testing", "success", "failure")) Text(when (state.connectionStatus) {
            "testing" -> "Checking connection…"
            "success" -> "Connection successful"
            "failure" -> "Couldn’t connect. Check your provider details and try again."
            else -> ""
        }, color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("provider_connection_status"))
        state.actionMessage?.takeIf { showProvider && accountIssue == null && (state.providerSaving || it == "Provider settings saved." || it == "Missing provider fields." || it == "Recovered your saved provider details.") }?.let {
            Text(it, color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("provider_action_message"))
        }
    }
}

@Composable
private fun ProviderTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    testTag: String,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = AtlasMutedText, style = MaterialTheme.typography.bodyLarge)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = AtlasText),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(if (focused) AtlasSurfaceFocused else AtlasSurface, shape)
                .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AtlasAccent else AtlasSurfaceFocused), shape)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .onFocusChanged { focused = it.isFocused }
                .testTag(testTag)
                .semantics { contentDescription = label },
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    innerTextField()
                }
            },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsButton(text: String, onClick: () -> Unit, testTag: String, width: Int = 220, height: Int = 58, modifier: Modifier = Modifier, selected: Boolean? = null) {
    var focused by remember { mutableStateOf(false) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .widthIn(max = width.dp).fillMaxWidth()
            .height(height.dp)
            .background(if (focused || selected == true) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AtlasAccent else AtlasSurface), shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.DirectionCenter)) { if (event.nativeKeyEvent.repeatCount == 0) onClick(); true } else false
            }
            .clickable(onClick = onClick)
            .testTag(testTag)
            .semantics { contentDescription = text; role = if (selected != null) Role.Tab else Role.Button; if (selected != null) this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AtlasText, style = MaterialTheme.typography.titleMedium)
    }
}

private fun CatalogSyncStatus.label(): String =
    when (this) {
        CatalogSyncStatus.Idle -> "never synced"
        CatalogSyncStatus.Loading -> "syncing"
        CatalogSyncStatus.Success -> "success"
        CatalogSyncStatus.Failure -> "failed"
    }
