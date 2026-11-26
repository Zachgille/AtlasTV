package com.atlas.atlastv.ui.movies

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovieCategoryPreviewTest {
    private val first = MovieItem("Selected", "Drama", 1, "mp4", "room://safe")
    @Test fun onlyAvailableSameCategoryUniqueIdsRemainInProviderOrder() {
        val second = first.copy(streamId = 2, title = "Same title")
        val third = second.copy(streamId = 3)
        assertEquals(listOf(second, third), movieCategoryCandidates(first, listOf(first,
            second.copy(available = false), second, first.copy(streamId = 4, categoryName = "Comedy"), second, third)))
    }
    @Test fun singletonBlankAndUnavailableCategoriesNeverBorrowUnrelatedMovies() {
        assertTrue(movieCategoryCandidates(first, listOf(first, first.copy(streamId = 2, categoryName = "Comedy"))).isEmpty())
        assertTrue(movieCategoryCandidates(first.copy(categoryName = ""), listOf(first.copy(streamId = 2, categoryName = ""))).isEmpty())
        assertTrue(movieCategoryCandidates(first.copy(available = false), listOf(first.copy(streamId = 2))).isEmpty())
        assertTrue(movieCategoryCandidates(first, listOf(first.copy(streamId = 2)), 0).isEmpty())
    }
    @Test fun boundNeverDropsOriginalIdentityOrConflatesDifferentIdsWithTheSameTitle() {
        val items = (2..30).map { first.copy(streamId = it, title = "A Movie (2020) HEVC") }
        val result = movieCategoryCandidates(first, items)
        assertEquals((2..13).toList(), result.map { it.streamId })
        assertTrue(result.all { it.title == "A Movie (2020) HEVC" })
    }
    @Test fun sourcePreviewReachesBeyondLoadedBrowseAndNeverWritesPersonalState() = runTest {
        val browse = MoviesCatalogState.Ready("check", 0, listOf(first, first.copy(streamId = 2, categoryName = "Comedy")), emptyList())
        var calls = 0
        val source = object : MoviesCatalogSource {
            override suspend fun load(previous: MoviesCatalogState.Ready?) = browse
            override suspend fun categoryPreview(movie: MovieItem, maxItems: Int): List<MovieItem> {
                calls++; assertEquals(first, movie); assertEquals(12, maxItems)
                return (10..30).map { first.copy(streamId = it) } + first.copy(streamId = 99, categoryName = "Comedy")
            }
            override suspend fun savePreferences(preferences: MoviesPreferences) { error("Unexpected write") }
            override suspend fun saveWatchlist(movies: List<MovieItem>, ids: Set<Int>) { error("Unexpected write") }
        }
        val holder = MoviesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        holder.ensureCachedCatalog()
        assertEquals((10..21).toList(), holder.categoryPreviewFor(first).map { it.streamId }); assertEquals(1, calls)
        assertEquals(browse, holder.state)
    }
    @Test fun retainedNavigationReturnsNestedParentsAndClearStartsANewRoot() {
        val navigation = MovieCategoryNavigationState()
        val child = first.copy(streamId = 2); val grandchild = first.copy(streamId = 3)
        navigation.openChild(first); navigation.openChild(child)
        assertEquals(child, navigation.returnParent(grandchild.streamId)); assertEquals(3, navigation.restoreMovieId)
        assertEquals(first, navigation.returnParent(child.streamId)); assertEquals(2, navigation.restoreMovieId)
        assertNull(navigation.returnParent(first.streamId))
        navigation.openChild(first); navigation.clear(); assertNull(navigation.returnParent(child.streamId)); assertNull(navigation.restoreMovieId)
    }
    @Test fun optionalFailureHasNoUnrelatedFallbackAndCancellationPropagates() = runTest {
        var cancel = false
        val source = object : MoviesCatalogSource {
            override suspend fun load(previous: MoviesCatalogState.Ready?) = MoviesCatalogState.Ready("check", 0, listOf(first, first.copy(streamId = 2)), emptyList())
            override suspend fun categoryPreview(movie: MovieItem, maxItems: Int): List<MovieItem> {
                if (cancel) throw CancellationException("left") else error("private provider diagnostic")
            }
        }
        val holder = MoviesCatalogStateHolder(source, backgroundScope, StandardTestDispatcher(testScheduler))
        holder.ensureCachedCatalog(); assertTrue(holder.categoryPreviewFor(first).isEmpty())
        cancel = true
        try { holder.categoryPreviewFor(first); fail("Cancellation must propagate") } catch (_: CancellationException) {}
    }
}
