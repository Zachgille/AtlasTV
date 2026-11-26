package com.atlas.atlastv.ui.settings

import android.content.Context
import com.atlas.atlastv.BuildConfig
import com.atlas.atlastv.core.config.DebugConfig
import java.io.File

object ProviderSettingsDebugBootstrap {
    fun bootstrapIfEmpty(context: Context): Boolean {
        if (!BuildConfig.DEBUG) return false
        val credentials = DebugConfig.debugXtreamCredentials ?: return false
        val appContext = context.applicationContext
        val store = ProviderSettingsStore(File(appContext.filesDir, ProviderSettingsStore.FileName))
        return bootstrapIfEmpty(
            store = store,
            isDebugBuild = BuildConfig.DEBUG,
            settings = credentials.toProviderSettingsSnapshot(),
        )
    }

    internal fun bootstrapIfEmpty(
        store: ProviderSettingsStore,
        isDebugBuild: Boolean,
        settings: ProviderSettingsSnapshot?,
    ): Boolean {
        if (!isDebugBuild) return false
        return ProviderSettingsBootstrap.bootstrapIfEmpty(store, settings)
    }
}
