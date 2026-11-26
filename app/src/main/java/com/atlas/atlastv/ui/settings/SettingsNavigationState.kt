package com.atlas.atlastv.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class SettingsSection(val title: String, val tag: String) {
    Library("Library", "settings_section_library"),
    Playback("Playback", "settings_section_playback"),
    Provider("Provider", "settings_section_provider"),
    Advanced("Advanced", "settings_section_advanced"),
}

/** UI-only process-lifetime context; provider drafts and pending writes remain in their existing holders. */
class SettingsNavigationState {
    var section by mutableStateOf(SettingsSection.Library)
    var returningFromDiagnostics = false
}
