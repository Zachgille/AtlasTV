package com.atlas.atlastv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val AtlasColors = darkColorScheme(
    background = AtlasBackground,
    surface = AtlasSurface,
    primary = AtlasAccent,
    onBackground = AtlasText,
    onSurface = AtlasText,
    onPrimary = AtlasOnAccent,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AtlasTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AtlasColors,
        content = content,
    )
}
