package io.github.klanary.jigeumchulbal.ui.routeedit

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.data.remote.ApiError
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.CandidateDto
import io.github.klanary.jigeumchulbal.data.remote.LegDto
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.RoutesDto
import io.github.klanary.jigeumchulbal.data.remote.StepDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
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

    // ── 구간별 열차 고르기(열차 고르기 설계 2026-11-18 §5.2) ──

    private fun t(hhmm: String) = "2026-11-18T$hhmm:00+09:00"

    private fun c(no: String, dep: String, arr: String) = CandidateDto(trainNo = no, departsAt = t(dep), arrivesAt = t(arr))

    private fun picked(id: String, five: List<CandidateDto>, nine: List<CandidateDto>, line2: String = "9호선"): RouteDto {
        fun step(line: String, from: String, to: String, list: List<CandidateDto>) = StepDto(
            mode = StepMode.SUBWAY, durationSec = 240, distanceM = 0, lineName = line,
            boardStationId = from, alightStationId = to, departsAt = list[0].departsAt, arrivesAt = list[0].arrivesAt,
            trainNo = list[0].trainNo, candidates = list,
        )
        return RouteDto(
            id = id, type = "SUBWAY", totalSec = 540, distanceM = 0, transfers = 1,
            legs = listOf(LegDto(listOf(step("5호선", "마포", "여의도", five), step(line2, "여의도", "노량진", nine)))),
            departAt = five[0].departsAt, arriveAt = nine[0].arrivesAt,
        )
    }

    private val pickRoute = picked(
        "r1",
        listOf(c("5301", "08:05", "08:09"), c("5303", "08:15", "08:19"), c("5305", "08:25", "08:29")),
        listOf(c("9101", "08:10", "08:14"), c("9103", "08:20", "08:24"), c("9105", "08:30", "08:34")),
    )

    private suspend fun kotlinx.coroutines.test.TestScope.searchedWith(route: RouteDto): RouteFlowViewModel {
        routes.result = ApiResult.Ok(RoutesDto(updatedAt = t("08:00"), routes = listOf(route)))
        val vm = vm(FakeLocationProvider(false, null))
        vm.withEnds()
        vm.search()
        advanceUntilIdle()
        vm.select(route.id)
        return vm
    }

    @Test fun moveTrainChangesPickedRouteAndTotal() = runTest {
        val vm = searchedWith(pickRoute)
        vm.moveTrain(0, 1)
        val state = vm.state.value
        assertEquals(t("08:15"), state.pickedRoute?.departAt)
        assertEquals(t("08:24"), state.pickedRoute?.arriveAt)
        assertEquals(listOf("5303", "9103"), state.picks.map { it.train?.trainNo })
        assertTrue(state.picks[0].pinned)
    }

    @Test fun selectResetsChoices() = runTest {
        val vm = searchedWith(pickRoute)
        vm.moveTrain(0, 1)
        vm.select(pickRoute.id)
        assertTrue(vm.state.value.choices.isEmpty())
        assertEquals("5301", vm.state.value.picks[0].train?.trainNo)
    }

    @Test fun loadLaterMergesCandidatesOfMatchingRoute() = runTest {
        val vm = searchedWith(pickRoute)
        val later = picked(
            "r1",
            listOf(c("5305", "08:25", "08:29"), c("5307", "08:35", "08:39")),
            listOf(c("9105", "08:30", "08:34"), c("9107", "08:40", "08:44")),
        )
        routes.result = ApiResult.Ok(RoutesDto(updatedAt = t("08:00"), routes = listOf(later)))
        vm.loadLater(0)
        advanceUntilIdle()
        assertEquals(Instant.parse("2026-11-17T23:26:00Z"), routes.calls.last().departAt) // 08:25 + 1분
        val steps = vm.state.value.selectedRoute!!.legs.flatMap { it.steps }
        assertEquals(listOf("5301", "5303", "5305", "5307"), steps[0].candidates!!.map { it.trainNo })
        assertEquals(listOf("9101", "9103", "9105", "9107"), steps[1].candidates!!.map { it.trainNo })
        assertFalse(vm.state.value.loadingMore)
        assertNull(vm.state.value.departAt) // 검색 조건은 그대로
    }

    @Test fun loadLaterWithoutMatchingRouteKeepsState() = runTest {
        val vm = searchedWith(pickRoute)
        val other = picked("r2", listOf(c("5307", "08:35", "08:39")), listOf(c("2001", "08:40", "08:44")), line2 = "2호선")
        routes.result = ApiResult.Ok(RoutesDto(updatedAt = t("08:00"), routes = listOf(other)))
        vm.loadLater(0)
        advanceUntilIdle()
        assertEquals(pickRoute, vm.state.value.selectedRoute)
        assertFalse(vm.state.value.loadingMore)
        assertEquals(setOf(0), vm.state.value.exhausted) // 더 불러올 수 없음을 표시(최종 리뷰 #4)
    }

    // ── 최종 리뷰 #2, #3, #4 ──
    @Test fun hasPicksOnlyAfterUserChoice() = runTest {
        val vm = searchedWith(pickRoute)
        assertFalse(vm.state.value.hasPicks)
        vm.moveTrain(1, 1)
        assertTrue(vm.state.value.hasPicks)
    }

    @Test fun loadLaterForLaterSegmentShiftsBackByOffset() = runTest {
        // 2구간은 경로 출발(08:05)보다 5분 뒤에 탄다 → 마지막 후보 08:30 + 1분 − 5분 = 08:26에 출발하는 경로를 받는다.
        val vm = searchedWith(pickRoute)
        vm.loadLater(1)
        advanceUntilIdle()
        assertEquals(Instant.parse("2026-11-17T23:26:00Z"), routes.calls.last().departAt)
    }

    @Test fun loadLaterWithNothingNewMarksSegmentExhausted() = runTest {
        val vm = searchedWith(pickRoute) // 같은 응답이 다시 온다: 새 후보 없음
        vm.loadLater(0)
        advanceUntilIdle()
        assertEquals(setOf(0), vm.state.value.exhausted)
        vm.select(pickRoute.id)
        assertTrue(vm.state.value.exhausted.isEmpty())
    }

    // ── 미뤄 둔 문제 #5: 다른 경로를 고르면 불러오기를 취소 ──
    @Test fun selectCancelsPendingLoadLater() = runTest {
        val vm = searchedWith(pickRoute)
        val later = picked("r1", listOf(c("5307", "08:35", "08:39")), listOf(c("9107", "08:40", "08:44")))
        routes.result = ApiResult.Ok(RoutesDto(updatedAt = t("08:00"), routes = listOf(later)))
        vm.loadLater(0)
        vm.select(pickRoute.id) // 응답이 오기 전에 다시 고름
        advanceUntilIdle()
        assertEquals(3, vm.state.value.selectedRoute!!.legs.flatMap { it.steps }[0].candidates!!.size)
        assertFalse(vm.state.value.loadingMore)
    }
}
