package com.atlas.atlastv.ui.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.theme.AtlasBackground
import com.atlas.atlastv.ui.theme.AtlasMutedText

@Composable
fun PreparingPlaybackScreen(failed: Boolean, onRetry: () -> Unit, onBack: () -> Unit) {
    val primary = remember { FocusRequester() }
    val activationKeysDown = remember { mutableSetOf<Key>() }
    BackHandler(onBack = onBack)
    LaunchedEffect(failed) { primary.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(AtlasBackground).testTag("preparing_playback")
            .onPreviewKeyEvent { event ->
                if (event.key !in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar)) false
                else when (event.type) {
                    KeyEventType.KeyDown -> { activationKeysDown.add(event.key); false }
                    // The launch key may be released after this screen takes focus. It must
                    // not click Back unless its press also started on this screen.
                    KeyEventType.KeyUp -> !activationKeysDown.remove(event.key)
                    else -> false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        Text(if (failed) "Couldn't get your video ready" else "Getting your video ready")
        if (failed) Text("Try again. Your saved progress is kept.", color = AtlasMutedText)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (failed) Button(onClick = onRetry, modifier = Modifier.focusRequester(primary).testTag("resume_lookup_retry")) { Text("Retry") }
            Button(onClick = onBack, modifier = Modifier.then(if (failed) Modifier else Modifier.focusRequester(primary)).testTag("resume_lookup_back")) { Text("Back") }
        }
    }
}
