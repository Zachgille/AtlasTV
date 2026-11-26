package com.atlas.atlastv.ui.mylist

import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import com.atlas.atlastv.ui.media.AtlasImageRef

enum class MyListType(val storageKey: String) { Movie("movie"), Show("series") }
data class MyListKey(val type: MyListType, val id: Int)
enum class MyListFilter(val label: String, val storageKey: String) { All("All", ""), Movies("Movies", "movie"), Shows("Shows", "series") }

sealed interface MyListItem {
    val key: MyListKey
    val title: String
    val image: AtlasImageRef
    val subtitle: String
    data class Movie(val item: MovieItem) : MyListItem {
        override val key get() = MyListKey(MyListType.Movie, item.streamId)
        override val title get() = item.title
        override val image get() = item.posterImage
        override val subtitle get() = if (item.available) "Movie" else "Unavailable movie"
    }
    data class Show(val item: SeriesItem) : MyListItem {
        override val key get() = MyListKey(MyListType.Show, item.seriesId)
        override val title get() = item.name
        override val image get() = item.posterImage
        override val subtitle get() = if (item.available) "Show" else "Unavailable show"
    }
}
