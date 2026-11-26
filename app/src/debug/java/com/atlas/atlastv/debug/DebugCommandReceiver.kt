package com.atlas.atlastv.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DEBUG_COMMAND) return

        val pendingResult = goAsync()
        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
        val streamId = intent.getStringExtra(EXTRA_STREAM_ID)?.toIntOrNull()
        val durationSeconds = intent.getStringExtra(EXTRA_DURATION_SECONDS)?.toIntOrNull()
        Log.i(TAG, "Received command=${command.ifBlank { "unknown" }}")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            DebugCommandExecutor(context).execute(command = command, streamId = streamId, durationSeconds = durationSeconds)
            if (!command.isLongRunningDebugCommand()) {
                pendingResult.finish()
            }
        }
        if (command.isLongRunningDebugCommand()) {
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_DEBUG_COMMAND = "com.atlas.atlastv.DEBUG_COMMAND"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_STREAM_ID = "stream_id"
        const val EXTRA_DURATION_SECONDS = "duration_seconds"
        private const val TAG = "Lumaire/DebugCommand"
    }
}

private fun String.isLongRunningDebugCommand(): Boolean =
    trim().equals("probe_playback_deep_health", ignoreCase = true)
