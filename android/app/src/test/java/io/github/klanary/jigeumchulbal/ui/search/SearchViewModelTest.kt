package io.github.klanary.jigeumchulbal.ui.search

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.local.RecentSearchEntity
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.data.remote.ApiError
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.fakes.FakeRecentSearchRepository
import io.github.klanary.jigeumchulbal.fakes.FakeStationRepository
import io.github.klanary.jigeumchulbal.ui.PickResult
import io.github.klanary.jigeumchulbal.ui.Slot
import io.github.klanary.jigeumchulbal.ui.common.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val stations = FakeStationRepository()
    private val recents = FakeRecentSearchRepository(
        listOf(RecentSearchEntity(1, "합정", "합정", 1), RecentSearchEntity(2, "홍대입구", "홍대입구", 2)),
    )

    private fun vm() = SearchViewModel(Slot.DEST, stations, recents)

    @Test fun blankQueryDoesNotCallServer() = runTest {
        val vm = vm()
        vm.onQueryChange("  ")
        advanceUntilIdle()
        assertTrue(stations.searchCalls.isEmpty())
    }

    @Test fun typingIsDebouncedTo300ms() = runTest {
        val vm = vm()
        vm.onQueryChange("ㅎㄷ")
        advanceTimeBy(100)
        vm.onQueryChange("ㅎㄷㅇㄱ")
        advanceUntilIdle()
        assertEquals(listOf("ㅎㄷㅇㄱ"), stations.searchCalls)
    }

    @Test fun resultsAreStationsOnly() = runTest {
        val vm = vm()
        vm.onQueryChange("홍대입구")
        advanceUntilIdle()
        assertEquals(listOf("홍대입구"), (vm.state.value.stations as UiState.Content).data.map { it.id })
    }

    @Test fun errorIsShownAsError() = runTest {
        stations.result = ApiResult.Err(ApiError.Network)
        val vm = vm()
        vm.onQueryChange("홍대")
        advanceUntilIdle()
        assertTrue(vm.state.value.stations is UiState.Error)
    }

    @Test fun recentsAreFilteredByQuery() = runTest {
        val vm = vm()
        vm.onQueryChange("홍")
        advanceUntilIdle()
        assertEquals(listOf("홍대입구"), vm.state.value.recents.map { it.stationId })
    }

    @Test fun selectingRecordsRecentAndReturnsPick() = runTest {
        val station = StationRef("홍대입구", "홍대입구")
        assertEquals(PickResult(Slot.DEST, station), vm().select(station))
        assertEquals(listOf(station), recents.recorded)
    }
}
