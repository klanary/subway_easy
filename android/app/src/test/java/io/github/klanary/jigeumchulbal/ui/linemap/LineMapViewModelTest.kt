package io.github.klanary.jigeumchulbal.ui.linemap

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.NearestStationDto
import io.github.klanary.jigeumchulbal.fakes.FakeLocationProvider
import io.github.klanary.jigeumchulbal.fakes.FakeStationRepository
import io.github.klanary.jigeumchulbal.ui.common.Copy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LineMapViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val data = LineMapStations(100f, 100f, listOf(MapStation("시청", 10f, 20f), MapStation("서울", 30f, 40f)))
    private val stations = FakeStationRepository()
    private val here = GeoPoint(37.5665, 126.978)

    private fun vm(permission: Boolean = true) =
        LineMapViewModel({ data }, stations, FakeLocationProvider(permission, here), Dispatchers.Main)

    @Test fun loadsStationTable() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(data, vm.state.value.map)
    }

    @Test fun locateFocusesNearestStationOnMap() = runTest {
        stations.nearestResult = ApiResult.Ok(NearestStationDto("시청", "시청", 90))
        val vm = vm()
        advanceUntilIdle()
        vm.locate()
        advanceUntilIdle()
        assertEquals(MapStation("시청", 10f, 20f), vm.state.value.focus)
        vm.consumeFocus()
        assertNull(vm.state.value.focus)
    }

    @Test fun noStationNearbyShowsMessage() = runTest {
        stations.nearestResult = ApiResult.Ok(null)
        val vm = vm()
        advanceUntilIdle()
        vm.locate()
        advanceUntilIdle()
        assertNull(vm.state.value.focus)
        assertEquals(Copy.NO_STATION_NEARBY, vm.state.value.message)
    }

    @Test fun stationNotOnMapShowsMessage() = runTest {
        stations.nearestResult = ApiResult.Ok(NearestStationDto("원곡", "원곡", 300))
        val vm = vm()
        advanceUntilIdle()
        vm.locate()
        advanceUntilIdle()
        assertEquals(Copy.STATION_NOT_ON_MAP, vm.state.value.message)
    }

    @Test fun pickingStationsBuildsTrip() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.selectStation("마포")
        assertEquals("마포", vm.state.value.selected)
        vm.setRole(TripRole.FROM, StationRef("마포", "마포"))
        assertNull(vm.state.value.selected)
        assertNull(vm.state.value.readyTrip)
        vm.setRole(TripRole.TO, StationRef("공덕", "공덕"))
        assertEquals(TripDraft(StationRef("마포", "마포"), null, StationRef("공덕", "공덕")), vm.state.value.readyTrip)
        vm.consumeReadyTrip()
        assertNull(vm.state.value.readyTrip)
        // 결과를 본 뒤 돌아오면 같은 출발·도착이 남아 경유를 더할 수 있다.
        assertEquals("마포", vm.state.value.draft.from?.id)
    }

    @Test fun sameStationCannotBeBothEnds() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.setRole(TripRole.FROM, StationRef("마포", "마포"))
        vm.setRole(TripRole.TO, StationRef("마포", "마포"))
        assertNull(vm.state.value.draft.to)
    }

    @Test fun cancelClearsTrip() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.setRole(TripRole.FROM, StationRef("마포", "마포"))
        vm.cancelTrip()
        assertEquals(TripDraft(), vm.state.value.draft)
    }
}
