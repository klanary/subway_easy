package io.github.klanary.jigeumchulbal.ui.home

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteEntity
import io.github.klanary.jigeumchulbal.data.local.RouteUsageEntity
import io.github.klanary.jigeumchulbal.fakes.FakeFavoriteRepository
import io.github.klanary.jigeumchulbal.fakes.FakeServerRepository
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun wakesServerOnce() = runTest {
        val server = FakeServerRepository()
        HomeViewModel(FakeFavoriteRepository(), server)
        advanceUntilIdle()
        assertEquals(1, server.wakeCalls)
    }

    @Test fun favoriteRoutesSortedByTimeOfDay() = runTest {
        val zone = ZoneId.of("Asia/Seoul")
        val now = ZonedDateTime.of(2026, 11, 20, 8, 10, 0, 0, zone)
        fun fav(id: Long) = FavoriteRouteEntity(id, "마포", "마포", null, null, "공덕$id", "공덕$id", 0)
        val favorites = FakeFavoriteRepository().apply {
            routes.value = listOf(fav(1), fav(2))
            // 2번은 아침 8시 무렵에 썼고 1번은 저녁에만 썼다.
            usages.value = listOf(
                RouteUsageEntity(routeId = 2, usedAt = now.minusDays(1).minusMinutes(20).toInstant().toEpochMilli()),
                RouteUsageEntity(routeId = 1, usedAt = now.minusDays(1).withHour(19).toInstant().toEpochMilli()),
            )
        }
        val vm = HomeViewModel(favorites, FakeServerRepository(), clock = { now.toInstant().toEpochMilli() }, zone = zone)
        advanceUntilIdle()
        assertEquals(listOf(2L, 1L), vm.state.value.favoriteRoutes.map { it.id })
    }
}
