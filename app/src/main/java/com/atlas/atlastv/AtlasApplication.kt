package com.atlas.atlastv

import android.app.Application
import androidx.work.Configuration

/** WorkManager opens lazily, away from the first usable Home frame. */
class AtlasApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()
}
