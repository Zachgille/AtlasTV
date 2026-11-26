package com.atlas.atlastv.ui.browse

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogGridReadinessTest {
    private fun window(index: Int): CatalogGridWindow<Int> {
        val anchor = index.coerceIn(0,10000); val offset = maxOf(0,anchor/40-2)*40
        return CatalogGridWindow(10001,offset,anchor,(offset until minOf(10001,offset+200)).toList())
    }
    @Test fun nonemptyRoomGridDoesNotReloadOnBrowseReadinessButStillReloadsCatalogRevision() = runTest {
        var calls=0
        val holder=CatalogGridStateHolder(CatalogGridSource<Int> { index,_ -> calls++; window(index) },{ it },"safe",StandardTestDispatcher(testScheduler))
        val first=backgroundScope.launch { holder.activate(0,false) }; runCurrent()
        holder.select(6000); runCurrent(); holder.select(6000); first.cancelAndJoin()
        val second=backgroundScope.launch { holder.activate(0,true) }; runCurrent()
        assertEquals(2,calls); assertEquals(6000,holder.state.value.selectedIndex); assertNotNull(holder.state.value.item(6000))
        second.cancelAndJoin(); backgroundScope.launch { holder.activate(1,true) }; runCurrent()
        assertEquals(3,calls)
    }
    @Test fun missingDestinationCannotRestoreThePreviousTilesIdAfterCancellation() = runTest {
        val requests=mutableListOf<Pair<Int,Int?>>()
        val holder=CatalogGridStateHolder(CatalogGridSource<Int> { index,id -> requests+=index to id; window(id ?: index) },{ it },"safe",StandardTestDispatcher(testScheduler))
        val first=backgroundScope.launch { holder.activate(0) }; runCurrent()
        holder.select(10); holder.select(6000); first.cancelAndJoin()
        backgroundScope.launch { holder.activate(1) }; runCurrent()
        assertEquals(6000 to null,requests.last()); assertEquals(6000,holder.state.value.selectedIndex)
    }
}
