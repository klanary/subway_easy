package io.github.klanary.jigeumchulbal.ui.home

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.local.FavoriteStationEntity
import io.github.klanary.jigeumchulbal.data.remote.ApiError
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.fakes.FakeFavoriteRepository
import io.github.klanary.jigeumchulbal.fakes.FakeMyPlaceRepository
import io.github.klanary.jigeumchulbal.fakes.FakeServerRepository
import io.github.klanary.jigeumchulbal.fakes.FakeStationRepository
import io.github.klanary.jigeumchulbal.ui.common.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun fav(id: String) = FavoriteStationEntity(stationId = id, createdAt = 0)

    @Test fun wakesServerOnce() = runTest {
        val server = FakeServerRepository()
        HomeViewModel(FakeMyPlaceRepository(), FakeFavoriteRepository(), FakeStationRepository(), server)
        advanceUntilIdle()
        assertEquals(1, server.wakeCalls)
    }

    @Test fun oneFailingFavoriteDoesNotBreakOthers() = runTest {
        val favorites = FakeFavoriteRepository(stations = listOf(fav("홍대입구"), fav("합정")))
        val stations = FakeStationRepository().apply {
            arrivalsByStation = mapOf("홍대입구" to ApiResult.Err(ApiError.Network))
        }
        val vm = HomeViewModel(FakeMyPlaceRepository(), favorites, stations, FakeServerRepository())
        advanceUntilIdle()
        assertEquals(listOf(true, false), vm.state.value.favoriteStations.map { it.arrivals is UiState.Error })
    }

    @Test fun favoriteWithLineRequestsThatLine() = runTest {
        val favorites = FakeFavoriteRepository(stations = listOf(FavoriteStationEntity("홍대입구", "2호선", null, 0)))
        val stations = FakeStationRepository()
        HomeViewModel(FakeMyPlaceRepository(), favorites, stations, FakeServerRepository())
        advanceUntilIdle()
        assertEquals(Triple("홍대입구", "2호선", null), stations.arrivalCalls.single())
    }
}
