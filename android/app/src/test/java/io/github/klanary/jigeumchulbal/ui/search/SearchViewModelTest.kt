package io.github.klanary.jigeumchulbal.ui.search

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.model.Endpoint
import io.github.klanary.jigeumchulbal.data.model.EndpointKind
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.remote.ApiError
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.fakes.FakeLocationProvider
import io.github.klanary.jigeumchulbal.fakes.FakeMyPlaceRepository
import io.github.klanary.jigeumchulbal.fakes.FakePlaceRepository
import io.github.klanary.jigeumchulbal.fakes.FakeRecentSearchRepository
import io.github.klanary.jigeumchulbal.fakes.FakeStationRepository
import io.github.klanary.jigeumchulbal.location.LocationProvider
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
    private val places = FakePlaceRepository()
    private val recents = FakeRecentSearchRepository()
    private val here = GeoPoint(37.5571, 126.9245)

    private fun vm(location: LocationProvider = FakeLocationProvider(true, here)) =
        SearchViewModel(Slot.DEST, stations, places, FakeMyPlaceRepository(), recents, location)

    @Test fun blankQueryDoesNotCallServer() = runTest {
        val vm = vm()
        vm.onQueryChange("  ")
        advanceUntilIdle()
        assertTrue(stations.searchCalls.isEmpty() && places.searchCalls.isEmpty())
    }

    @Test fun typingIsDebouncedTo300ms() = runTest {
        val vm = vm()
        vm.onQueryChange("ㅎㄷ")
        advanceTimeBy(100)
        vm.onQueryChange("ㅎㄷㅇㄱ")
        advanceUntilIdle()
        assertEquals(listOf("ㅎㄷㅇㄱ"), stations.searchCalls)
    }

    @Test fun staleResponseNeverOverwritesNewerQuery() = runTest {
        places.delays = mapOf("홍" to 2_000L)
        val vm = vm()
        vm.onQueryChange("홍")
        advanceTimeBy(301)
        vm.onQueryChange("홍대")
        advanceUntilIdle()
        assertEquals(listOf("홍대 결과"), (vm.state.value.places as UiState.Content).data.map { it.name })
    }

    @Test fun placeSearchUsesCurrentLocationWhenPermitted() = runTest {
        val vm = vm()
        vm.onQueryChange("카페")
        advanceUntilIdle()
        assertEquals(here, places.lastNear)
    }

    @Test fun stationErrorDoesNotHidePlaces() = runTest {
        stations.result = ApiResult.Err(ApiError.Network)
        val vm = vm()
        vm.onQueryChange("홍대")
        advanceUntilIdle()
        assertTrue(vm.state.value.stations is UiState.Error && vm.state.value.places is UiState.Content)
    }

    @Test fun selectingRecordsRecentAndReturnsPick() = runTest {
        val endpoint = Endpoint("홍대입구", here, EndpointKind.STATION, "홍대입구")
        assertEquals(PickResult(Slot.DEST, endpoint), vm().select(endpoint))
        assertEquals(listOf(endpoint), recents.recorded)
    }
}
