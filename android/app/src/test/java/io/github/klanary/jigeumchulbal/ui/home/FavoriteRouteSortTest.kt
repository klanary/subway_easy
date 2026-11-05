package io.github.klanary.jigeumchulbal.ui.home

import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteEntity
import io.github.klanary.jigeumchulbal.data.local.RouteUsageEntity
import io.github.klanary.jigeumchulbal.data.model.Endpoint
import io.github.klanary.jigeumchulbal.data.model.EndpointKind
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteRouteSortTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone)
    private fun fav(id: Long, created: Long = 0) =
        FavoriteRouteEntity(id, "집", 37.0, 127.0, null, null, null, "학교", 37.5, 127.5, created)
    private fun use(id: Long, t: ZonedDateTime) = RouteUsageEntity(routeId = id, usedAt = t.toInstant().toEpochMilli())

    @Test fun countsUsesWithinOneHourOfNow() {
        val usages = listOf(use(1, at(17, 7, 30)), use(1, at(18, 8, 50)), use(2, at(17, 18, 0)), use(2, at(18, 18, 0)), use(2, at(19, 18, 0)))
        assertEquals(listOf(1L, 2L), sortFavoriteRoutes(listOf(fav(2), fav(1)), usages, at(20, 8, 10)).map { it.id })
    }

    @Test fun windowWrapsAroundMidnight() {
        val usages = listOf(use(1, at(19, 23, 45)), use(2, at(19, 2, 0)), use(2, at(18, 2, 0)))
        assertEquals(listOf(1L, 2L), sortFavoriteRoutes(listOf(fav(2), fav(1)), usages, at(20, 0, 30)).map { it.id })
    }

    @Test fun ignoresUsesOlderThan30Days() {
        val usages = listOf(use(2, at(1, 8, 0)), use(2, at(2, 8, 0)), use(1, at(19, 12, 0)))
        // 11/1·11/2 기록은 12/5 기준 30일 밖 → 둘 다 창 안 0회, 전체는 1이 1회 · 2가 0회
        val now = ZonedDateTime.of(2026, 12, 5, 8, 0, 0, 0, zone)
        assertEquals(listOf(1L, 2L), sortFavoriteRoutes(listOf(fav(2), fav(1)), usages, now).map { it.id })
    }

    @Test fun tieBreaksByMostRecentUse() { // 창 안 0회·전체 1회로 같을 때
        val usages = listOf(use(1, at(19, 12, 0)), use(2, at(18, 12, 0)))
        assertEquals(listOf(1L, 2L), sortFavoriteRoutes(listOf(fav(2), fav(1)), usages, at(20, 8, 0)).map { it.id })
    }

    @Test fun sameEndpointsUsesFourDecimals() {
        val f = fav(1)
        val dest = Endpoint("b", GeoPoint(37.5, 127.5), EndpointKind.PIN)
        assertTrue(sameEndpoints(f, Endpoint("a", GeoPoint(37.00004, 127.0), EndpointKind.PIN), null, dest))
        assertFalse(sameEndpoints(f, Endpoint("a", GeoPoint(37.0002, 127.0), EndpointKind.PIN), null, dest))
    }
}
