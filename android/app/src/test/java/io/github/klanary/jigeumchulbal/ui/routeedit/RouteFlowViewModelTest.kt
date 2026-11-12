package io.github.klanary.jigeumchulbal.ui.routeedit

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.data.remote.ApiError
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.NearestStationDto
import io.github.klanary.jigeumchulbal.fakes.FakeFavoriteRepository
import io.github.klanary.jigeumchulbal.fakes.FakeLocationProvider
import io.github.klanary.jigeumchulbal.fakes.FakeRouteRepository
import io.github.klanary.jigeumchulbal.fakes.FakeStationRepository
import io.github.klanary.jigeumchulbal.location.LocationProvider
import io.github.klanary.jigeumchulbal.ui.PickResult
import io.github.klanary.jigeumchulbal.ui.Slot
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.linemap.TripDraft
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteFlowViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val here = GeoPoint(37.5571, 126.9245)
    private val routes = FakeRouteRepository()
    private val stations = FakeStationRepository().apply {
        nearestResult = ApiResult.Ok(NearestStationDto("홍대입구", "홍대입구", 120))
    }
    private val favorites = FakeFavoriteRepository()
    private val hongdae = StationRef("홍대입구", "홍대입구")
    private val gangnam = StationRef("강남", "강남")
    private val cityHall = StationRef("시청", "시청")

    private fun vm(location: LocationProvider) = RouteFlowViewModel(routes, stations, location, favorites) { 1_000L }

    private suspend fun RouteFlowViewModel.withEnds() {
        apply(PickResult(Slot.ORIGIN, hongdae))
        apply(PickResult(Slot.DEST, gangnam))
    }

    @Test fun originDefaultsToNearestStationWhenPermitted() = runTest {
        val vm = vm(FakeLocationProvider(true, here))
        vm.init()
        advanceUntilIdle()
        assertEquals(hongdae, vm.state.value.origin)
        assertEquals(listOf(here.lat to here.lng), stations.nearestCalls)
    }

    @Test fun originEmptyWhenPermissionMissing() = runTest {
        val vm = vm(FakeLocationProvider(false, here))
        vm.init()
        advanceUntilIdle()
        assertNull(vm.state.value.origin)
        assertEquals(Copy.ORIGIN_HINT, vm.state.value.originHint)
    }

    @Test fun originEmptyWhenNoStationNearby() = runTest {
        stations.nearestResult = ApiResult.Ok(null)
        val vm = vm(FakeLocationProvider(true, here))
        vm.init()
        advanceUntilIdle()
        assertNull(vm.state.value.origin)
        assertEquals(Copy.NO_STATION_NEARBY, vm.state.value.originHint)
    }

    @Test fun swapExchangesEnds() = runTest {
        val vm = vm(FakeLocationProvider(false, null))
        vm.withEnds()
        vm.swap()
        assertEquals(gangnam to hongdae, vm.state.value.origin to vm.state.value.dest)
    }

    @Test fun sameStationCannotBeSearched() = runTest {
        val vm = vm(FakeLocationProvider(false, null))
        vm.apply(PickResult(Slot.ORIGIN, hongdae))
        vm.apply(PickResult(Slot.DEST, hongdae))
        assertFalse(vm.state.value.canSearch)
        vm.apply(PickResult(Slot.DEST, gangnam))
        vm.apply(PickResult(Slot.VIA, gangnam))
        assertFalse(vm.state.value.canSearch)
    }

    @Test fun searchShowsServerMessage() = runTest {
        val vm = vm(FakeLocationProvider(false, null))
        vm.withEnds()
        routes.result = ApiResult.Err(ApiError.Server("NO_ROUTE", "NO_SUBWAY", "지하철로 갈 수 있는 경로가 없어요."))
        vm.search()
        advanceUntilIdle()
        assertEquals(UiState.Error("지하철로 갈 수 있는 경로가 없어요."), vm.state.value.results)
    }

    @Test fun autoOpenSearchOnlyOnce() = runTest {
        val vm = vm(FakeLocationProvider(true, here))
        vm.init()
        advanceUntilIdle()
        assertTrue(vm.consumeAutoOpenSearch())
        assertFalse(vm.consumeAutoOpenSearch())
    }

    @Test fun successfulSearchKeepsReceiveTimeAndSelectsRoute() = runTest {
        val vm = vm(FakeLocationProvider(false, null))
        vm.withEnds()
        vm.search()
        advanceUntilIdle()
        assertEquals(1_000L, vm.state.value.receivedAt)
        vm.select("r_3f9a")
        assertEquals("r_3f9a", vm.state.value.selectedRoute!!.id)
    }

    @Test fun searchSendsViaAndHidingClearsIt() = runTest {
        val vm = vm(FakeLocationProvider(false, null))
        vm.withEnds()
        assertFalse(vm.state.value.viaVisible)
        vm.apply(PickResult(Slot.VIA, cityHall))
        vm.search()
        advanceUntilIdle()
        assertEquals(cityHall, routes.lastVia)
        vm.toggleVia()
        assertNull(vm.state.value.via)
    }

    @Test fun departAtIsSentAndCleared() = runTest {
        val vm = vm(FakeLocationProvider(false, null))
        vm.withEnds()
        val at = Instant.parse("2026-11-12T23:00:00Z")
        vm.setDepartAt(at)
        advanceUntilIdle()
        assertEquals(at, routes.calls.last().departAt)
        vm.setDepartAt(null)
        advanceUntilIdle()
        assertNull(routes.calls.last().departAt)
    }

    @Test fun searchRecordsUsageWhenStationsMatchFavorite() = runTest {
        favorites.add(hongdae, null, gangnam)
        val vm = vm(FakeLocationProvider(false, null))
        vm.withEnds()
        vm.search()
        advanceUntilIdle()
        assertEquals(listOf(1L to 1_000L), favorites.usages.value.map { it.routeId to it.usedAt })
        assertTrue(vm.state.value.isFavorite)
    }

    @Test fun searchOfNonFavoriteRecordsNothing() = runTest {
        val vm = vm(FakeLocationProvider(false, null))
        vm.withEnds()
        vm.search()
        advanceUntilIdle()
        assertTrue(favorites.usages.value.isEmpty())
        assertFalse(vm.state.value.isFavorite)
    }

    @Test fun toggleFavoriteAddsThenRemoves() = runTest {
        val vm = vm(FakeLocationProvider(false, null))
        vm.withEnds()
        vm.toggleFavorite()
        advanceUntilIdle()
        assertTrue(vm.state.value.isFavorite)
        assertEquals("홍대입구", favorites.routes.value.single().fromId)
        vm.toggleFavorite()
        advanceUntilIdle()
        assertFalse(vm.state.value.isFavorite)
        assertTrue(favorites.routes.value.isEmpty())
    }

    @Test fun initFromFavoriteFillsStationsAndSearches() = runTest {
        favorites.add(hongdae, cityHall, gangnam)
        val vm = vm(FakeLocationProvider(false, null))
        vm.init(favoriteRouteId = 1)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf(hongdae, cityHall, gangnam), listOf(s.origin, s.via, s.dest))
        assertTrue(s.viaVisible)
        assertEquals(1, routes.calls.size)
        assertTrue(s.results is UiState.Content)
        // 결과에서 뒤로 와 편집 화면이 다시 그려져도 결과로 또 보내지 않는다.
        assertTrue(vm.consumeOpenResults())
        assertFalse(vm.consumeOpenResults())
    }

    @Test fun plainEntryNeverOpensResults() = runTest {
        val vm = vm(FakeLocationProvider(true, here))
        vm.init()
        advanceUntilIdle()
        assertFalse(vm.consumeOpenResults())
    }

    @Test fun initFromTripSearchesAndOpensResults() = runTest {
        val vm = vm(FakeLocationProvider(true, here))
        vm.init(trip = TripDraft(hongdae, cityHall, gangnam))
        advanceUntilIdle()
        assertEquals(listOf(hongdae, cityHall, gangnam), vm.state.value.let { listOf(it.origin, it.via, it.dest) })
        assertEquals(1, routes.calls.size)
        assertTrue(stations.nearestCalls.isEmpty()) // 출발을 현재 위치로 덮어쓰지 않는다
        assertTrue(vm.consumeOpenResults())
        assertFalse(vm.consumeAutoOpenSearch())
    }
}
