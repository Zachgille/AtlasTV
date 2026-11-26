package com.atlas.atlastv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMetadataTest {
    @Test
    fun appMetadataMatchesScaffoldRequirements() {
        assertEquals("Lumaire", AppMetadata.DisplayName)
        assertEquals("com.atlas.atlastv", AppMetadata.PackageName)
        assertTrue(AppMetadata.HomeSections.contains("Continue Watching"))
        assertTrue(AppMetadata.HomeSections.contains("Movies"))
        assertTrue(AppMetadata.HomeSections.contains("Series"))
        assertTrue(AppMetadata.HomeSections.contains("Search"))
        assertTrue(AppMetadata.HomeSections.contains("Settings"))
    }
}
