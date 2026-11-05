package io.github.klanary.jigeumchulbal.ui.routeedit

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.local.MyPlaceEntity
import io.github.klanary.jigeumchulbal.data.local.MyPlaceType
import io.github.klanary.jigeumchulbal.data.model.Endpoint
import io.github.klanary.jigeumchulbal.data.model.EndpointKind
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.remote.ApiError
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.fakes.FakeFavoriteRepository
import io.github.klanary.jigeumchulbal.fakes.FakeLocationProvider
import io.github.klanary.jigeumchulbal.fakes.FakeMyPlaceRepository
import io.github.klanary.jigeumchulbal.fakes.FakeRouteRepository
import io.github.klanary.jigeumchulbal.location.LocationProvider
import io.github.klanary.jigeumchulbal.ui.PickResult
import io.github.klanary.jigeumchulbal.ui.Slot
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.UiState
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
    private val myPlaces = FakeMyPlaceRepository(
        listOf(MyPlaceEntity(7, MyPlaceType.SCHOOL, "학교", 37.4979, 127.0276, null, "강남")),
    )

    private val favorites = FakeFavoriteRepository()
    private fun vm(location: LocationProvider) = RouteFlowViewModel(routes, myPlaces, location, favorites) { 1_000L }
    private val school = Endpoint("학교", GeoPoint(37.4979, 127.0276), EndpointKind.MY_PLACE, "강남")

    @Test fun originDefaultsToCurrentLocationWhenPermitted() = runTest {
        val vm = vm(FakeLocationProvider(true, here))
        vm.init(null)
        advanceUntilIdle()
        assertEquals(Endpoint("현재 위치", here, EndpointKind.CURRENT_LOCATION), vm.state.value.origin)
    }

    @Test fun originEmptyWhenPermissionMissing() = runTest {
        val vm = vm(FakeLocationProvider(false, here))
        vm.init(null)
        advanceUntilIdle()
        assertNull(vm.state.value.origin)
        assertEquals(Copy.ORIGIN_HINT, vm.state.value.originHint)
    }

    @Test fun originEmptyWhenLocationUnavailable() = runTest {
        val vm = vm(FakeLocationProvider(true, null))
        vm.init(null)
        advanceUntilIdle()
        assertNull(vm.state.value.origin)
        assertEquals(Copy.ORIGIN_HINT, vm.state.value.originHint)
    }

    @Test fun destPrefilledFromMyPlaceAndSwap() = runTest {
        val vm = vm(FakeLocationProvider(true, here))
        vm.init(7)
        advanceUntilIdle()
        assertEquals("학교", vm.state.value.dest!!.label)
        vm.swap()
        assertEquals("학교", vm.state.value.origin!!.label)
        assertEquals("현재 위치", vm.state.value.dest!!.label)
    }

    @Test fun searchNeedsBothEndsAndShowsServerMessage() = runTest {
        val vm = vm(FakeLocationProvider(false, null))
        vm.init(7)
        advanceUntilIdle()
        assertFalse(vm.state.value.canSearch)
        vm.apply(PickResult(Slot.ORIGIN, Endpoint("홍대입구", here, EndpointKind.STATION, "홍대입구")))
        routes.result = ApiResult.Err(ApiError.Server("NO_ROUTE", "NO_RESULTS", "대중교통 경로를 찾지 못했어요."))
        vm.search()
        advanceUntilIdle()
        assertEquals(UiState.Error("대중교통 경로를 찾지 못했어요."), vm.state.value.results)
    }

    @Test fun autoOpenSearchOnlyOnce() = runTest {
        val vm = vm(FakeLocationProvider(true, here))
        vm.init(null)
        advanceUntilIdle()
        assertTrue(vm.consumeAutoOpenSearch())
        assertFalse(vm.consumeAutoOpenSearch())
    }

    @Test fun successfulSearchKeepsReceiveTimeAndSelectsRoute() = runTest {
        val vm = vm(FakeLocationProvider(true, here))
        vm.init(7)
        advanceUntilIdle()
        vm.search()
        advanceUntilIdle()
        assertEquals(1_000L, vm.state.value.receivedAt)
        vm.select("r_3f9a")
        assertEquals("r_3f9a", vm.state.value.selectedRoute!!.id)
    }

    @Test fun viaHiddenByDefaultAndToggles() = runTest {
        val vm = vm(FakeLocationProvider(true, here)); vm.init(null); advanceUntilIdle()
        assertFalse(vm.state.value.viaVisible)
        vm.toggleVia()
        assertTrue(vm.state.value.viaVisible)
    }

    @Test fun searchSendsVia() = runTest {
        val vm = vm(FakeLocationProvider(true, here)); vm.init(7); advanceUntilIdle()
        val via = Endpoint("경복궁", GeoPoint(37.5759, 126.9735), EndpointKind.STATION, "경복궁")
        vm.apply(PickResult(Slot.VIA, via)); vm.search(); advanceUntilIdle()
        assertEquals(via, routes.lastVia)
    }

    @Test fun hidingViaClearsIt() = runTest {
        val vm = vm(FakeLocationProvider(true, here)); vm.init(null); advanceUntilIdle()
        vm.apply(PickResult(Slot.VIA, Endpoint("경복궁", GeoPoint(37.5759, 126.9735), EndpointKind.PIN)))
        vm.toggleVia()
        assertNull(vm.state.value.via)
    }

    @Test fun searchRecordsUsageWhenEndpointsMatchFavorite() = runTest {
        favorites.addRoute(Endpoint("집", here, EndpointKind.PIN), null, school)
        val vm = vm(FakeLocationProvider(true, here)); vm.init(7); advanceUntilIdle()
        vm.search(); advanceUntilIdle()
        assertEquals(listOf(1L to 1_000L), favorites.usages.value.map { it.routeId to it.usedAt })
        assertTrue(vm.state.value.isFavorite)
    }

    @Test fun searchOfNonFavoriteRecordsNothing() = runTest {
        val vm = vm(FakeLocationProvider(true, here)); vm.init(7); advanceUntilIdle()
        vm.search(); advanceUntilIdle()
        assertTrue(favorites.usages.value.isEmpty())
        assertFalse(vm.state.value.isFavorite)
    }

    @Test fun toggleFavoriteAddsThenRemoves() = runTest {
        val vm = vm(FakeLocationProvider(true, here)); vm.init(7); advanceUntilIdle()
        vm.toggleFavorite(); advanceUntilIdle()
        assertTrue(vm.state.value.isFavorite); assertEquals(1, favorites.routes.value.size)
        vm.toggleFavorite(); advanceUntilIdle()
        assertFalse(vm.state.value.isFavorite); assertTrue(favorites.routes.value.isEmpty())
    }

    @Test fun currentLocationIsNotSavedUnderItsLiveLabel() = runTest {
        val vm = vm(FakeLocationProvider(true, here)); vm.init(7); advanceUntilIdle()
        vm.toggleFavorite(); advanceUntilIdle()
        assertEquals(Copy.SAVED_LOCATION, favorites.routes.value.single().originLabel)
    }

    @Test fun initFromFavoriteFillsEndpointsAndSearches() = runTest {
        val via = Endpoint("경복궁", GeoPoint(37.5759, 126.9735), EndpointKind.STATION, "경복궁")
        favorites.addRoute(Endpoint("집", here, EndpointKind.PIN), via, school)
        val vm = vm(FakeLocationProvider(false, null)); vm.init(destMyPlaceId = null, favoriteRouteId = 1); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf("집", "경복궁", "학교"), listOf(s.origin?.label, s.via?.label, s.dest?.label))
        assertTrue(s.viaVisible)
        assertEquals(1, routes.calls.size)
        assertTrue(s.results is UiState.Content)
    }
}
