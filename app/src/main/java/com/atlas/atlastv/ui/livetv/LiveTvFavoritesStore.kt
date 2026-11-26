package com.atlas.atlastv.ui.livetv

import com.google.gson.GsonBuilder
import java.io.File

class LiveTvFavoritesStore(
    private val file: File,
) {
    fun loadFavoriteIds(): Set<Int> =
        runCatching {
            if (!file.exists()) return emptySet()
            json.fromJson(file.readText(Charsets.UTF_8), FavoritesSnapshot::class.java)
                ?.favorites
                .orEmpty()
                .map { it.streamId }
                .toSet()
        }.getOrDefault(emptySet())

    fun saveFavorites(channels: List<LiveTvChannel>, favoriteStreamIds: Set<Int>) {
        val favoriteItems = channels
            .filter { it.streamId in favoriteStreamIds }
            .map { channel ->
                FavoriteItem(
                    streamId = channel.streamId,
                    name = channel.name,
                    categoryName = channel.categoryName,
                )
            }
        val snapshot = FavoritesSnapshot(favorites = favoriteItems)
        file.writeText(json.toJson(snapshot), Charsets.UTF_8)
    }

    fun clear() {
        if (file.exists()) {
            file.delete()
        }
    }

    companion object {
        const val FileName = "live_tv_favorites.json"
        private val json = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    }
}

private data class FavoritesSnapshot(
    val favorites: List<FavoriteItem> = emptyList(),
)

private data class FavoriteItem(
    val streamId: Int,
    val name: String? = null,
    val categoryName: String? = null,
)
