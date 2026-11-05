package io.github.klanary.jigeumchulbal.ui.home

import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteEntity
import io.github.klanary.jigeumchulbal.data.local.RouteUsageEntity
import io.github.klanary.jigeumchulbal.data.model.Endpoint
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

private const val MINUTES_PER_DAY = 24 * 60
private const val WINDOW_MINUTES = 60
private const val LOOKBACK_DAYS = 30L

/** 하루 안의 두 시각(0~1439분) 사이 거리. 자정을 넘어 이어 센다(23:45와 00:30은 45분). */
fun circularMinuteDistance(a: Int, b: Int): Int {
    val d = abs(a - b)
    return min(d, MINUTES_PER_DAY - d)
}

/**
 * 자주 가는 경로 정렬(스펙 7.2). 최근 30일 기록만 보고,
 * (지금 시:분 ±1시간 안 사용 수, 30일 전체 사용 수, 마지막 사용 시각, 만든 시각) 내림차순.
 */
fun sortFavoriteRoutes(
    routes: List<FavoriteRouteEntity>,
    usages: List<RouteUsageEntity>,
    now: ZonedDateTime,
): List<FavoriteRouteEntity> {
    val since = now.minusDays(LOOKBACK_DAYS).toInstant().toEpochMilli()
    val nowMinute = now.hour * 60 + now.minute
    val recent = usages.filter { it.usedAt >= since }.groupBy { it.routeId }
    fun minuteOfDay(millis: Long): Int =
        Instant.ofEpochMilli(millis).atZone(now.zone).let { it.hour * 60 + it.minute }
    return routes.sortedWith(
        compareByDescending<FavoriteRouteEntity> { route ->
            recent[route.id].orEmpty().count { circularMinuteDistance(minuteOfDay(it.usedAt), nowMinute) <= WINDOW_MINUTES }
        }
            .thenByDescending { recent[it.id].orEmpty().size }
            .thenByDescending { route -> recent[route.id]?.maxOf { it.usedAt } ?: Long.MIN_VALUE }
            .thenByDescending { it.createdAt },
    )
}

/** 즐겨찾기와 출발·경유·도착이 같은지. 좌표는 소수점 4자리(약 10m)로 비교하고, 경유지는 둘 다 없거나 같아야 한다. */
fun sameEndpoints(fav: FavoriteRouteEntity, origin: Endpoint, via: Endpoint?, dest: Endpoint): Boolean {
    fun same(lat: Double, lng: Double, e: Endpoint) = round4(lat) == round4(e.point.lat) && round4(lng) == round4(e.point.lng)
    val viaMatches = when {
        via == null -> fav.viaLat == null && fav.viaLng == null
        fav.viaLat == null || fav.viaLng == null -> false
        else -> same(fav.viaLat, fav.viaLng, via)
    }
    return same(fav.originLat, fav.originLng, origin) && same(fav.destLat, fav.destLng, dest) && viaMatches
}

private fun round4(value: Double): Long = (value * 10_000).roundToLong()
