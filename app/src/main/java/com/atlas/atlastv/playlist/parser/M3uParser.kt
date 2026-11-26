package com.atlas.atlastv.playlist.parser

class M3uParser {
    fun parse(input: String): PlaylistParseResult {
        val items = mutableListOf<PlaylistItem>()
        val warnings = mutableListOf<PlaylistParseWarning>()
        var pendingEntry: PendingExtinf? = null

        input.lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.trim()

            when {
                line.isEmpty() -> Unit
                line.equals(EXTM3U, ignoreCase = true) -> Unit
                line.startsWith(EXTINF_PREFIX, ignoreCase = true) -> {
                    pendingEntry?.let {
                        warnings += PlaylistParseWarning(
                            lineNumber = it.lineNumber,
                            message = "EXTINF entry was skipped because no stream URL followed it.",
                        )
                    }
                    pendingEntry = parseExtinf(lineNumber, rawLine)
                }
                line.startsWith("#") -> Unit
                pendingEntry != null -> {
                    val entry = pendingEntry ?: return@forEachIndexed
                    items += entry.toPlaylistItem(streamUrl = line)
                    if (!entry.hasDisplayNameSeparator) {
                        warnings += PlaylistParseWarning(
                            lineNumber = entry.lineNumber,
                            message = "EXTINF entry did not contain a display-name separator comma.",
                        )
                    }
                    pendingEntry = null
                }
                else -> {
                    warnings += PlaylistParseWarning(
                        lineNumber = lineNumber,
                        message = "Stream URL was ignored because it did not have a preceding EXTINF entry.",
                    )
                }
            }
        }

        pendingEntry?.let {
            warnings += PlaylistParseWarning(
                lineNumber = it.lineNumber,
                message = "EXTINF entry was skipped because no stream URL followed it.",
            )
        }

        return PlaylistParseResult(items = items, warnings = warnings)
    }

    private fun parseExtinf(lineNumber: Int, rawExtinf: String): PendingExtinf {
        val separatorIndex = rawExtinf.indexOfFirstCommaOutsideQuotes()
        val metadata = if (separatorIndex >= 0) rawExtinf.substring(0, separatorIndex) else rawExtinf
        val displayName = if (separatorIndex >= 0) {
            rawExtinf.substring(separatorIndex + 1).trim()
        } else {
            ""
        }
        val attributes = parseQuotedAttributes(metadata)

        return PendingExtinf(
            lineNumber = lineNumber,
            tvgId = attributes["tvg-id"],
            tvgName = attributes["tvg-name"],
            tvgLogo = attributes["tvg-logo"],
            groupTitle = attributes["group-title"],
            displayName = displayName,
            rawExtinf = rawExtinf,
            hasDisplayNameSeparator = separatorIndex >= 0,
        )
    }

    private fun parseQuotedAttributes(metadata: String): Map<String, String> =
        QuotedAttributeRegex.findAll(metadata)
            .associate { match ->
                val key = match.groupValues[1].lowercase()
                val value = match.groupValues[2]
                key to value
            }

    private fun String.indexOfFirstCommaOutsideQuotes(): Int {
        var insideQuotes = false
        forEachIndexed { index, char ->
            when (char) {
                '"' -> insideQuotes = !insideQuotes
                ',' -> if (!insideQuotes) return index
            }
        }
        return -1
    }

    private data class PendingExtinf(
        val lineNumber: Int,
        val tvgId: String?,
        val tvgName: String?,
        val tvgLogo: String?,
        val groupTitle: String?,
        val displayName: String,
        val rawExtinf: String,
        val hasDisplayNameSeparator: Boolean,
    ) {
        fun toPlaylistItem(streamUrl: String): PlaylistItem =
            PlaylistItem(
                tvgId = tvgId,
                tvgName = tvgName,
                tvgLogo = tvgLogo,
                groupTitle = groupTitle,
                displayName = displayName,
                streamUrl = streamUrl,
                rawExtinf = rawExtinf,
            )
    }

    private companion object {
        private const val EXTM3U = "#EXTM3U"
        private const val EXTINF_PREFIX = "#EXTINF:"
        private val QuotedAttributeRegex = Regex("([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"")
    }
}
