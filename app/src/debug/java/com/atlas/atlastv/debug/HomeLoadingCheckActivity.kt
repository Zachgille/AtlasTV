package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.home.AtlasHomeScreen
import com.atlas.atlastv.ui.home.HomeCard
import com.atlas.atlastv.ui.home.HomeContentRow
import com.atlas.atlastv.ui.home.HomeContentType
import com.atlas.atlastv.ui.home.HomeLoadResult
import com.atlas.atlastv.ui.home.HomeShellState
import com.atlas.atlastv.ui.home.HomeShellStateHolder
import com.atlas.atlastv.ui.theme.LumaireTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/** Controlled Home UI acceptance with no account, catalog, watch-state or network access. */
class HomeLoadingCheckActivity : ComponentActivity() {
    private val loads = Channel<Result<HomeLoadResult>>(Channel.UNLIMITED)
    val cancelledLoads = AtomicInteger()
    val holder = HomeShellStateHolder({
        try { loads.receive().getOrThrow() }
        catch (cancelled: CancellationException) { cancelledLoads.incrementAndGet(); throw cancelled }
    })
    private var destination by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LumaireTheme {
                if (destination != null) Text(destination!!, modifier = Modifier.testTag("home_check_destination"))
                else AtlasHomeScreen(
                    stateHolder = holder,
                    onSearchSelected = { destination = "Search selected" },
                    onLiveTvSelected = {}, onMoviesSelected = {}, onSeriesSelected = {},
                    onSettingsSelected = { destination = "Settings selected" },
                )
            }
        }
    }

    fun complete(title: String = "Saved movie", id: String = "saved") {
        val card = HomeCard(id, title, HomeContentType.Movie, "Movies", 1, progressLabel = "Resume at 4:15", statusText = "Saved")
        loads.trySend(Result.success(HomeLoadResult(HomeShellState.Ready("check", 0L,
            listOf(HomeContentRow("continue_watching", "Continue Watching", listOf(card))), card.id))))
    }
    fun fail() { loads.trySend(Result.failure(IllegalStateException("private diagnostic"))) }
    fun empty() { loads.trySend(Result.success(HomeLoadResult(HomeShellState.Error("Open Settings to add a provider.", needsSetup = true)))) }
}
