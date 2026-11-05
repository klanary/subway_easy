package io.github.klanary.jigeumchulbal.data.repository

import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteDao
import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteEntity
import io.github.klanary.jigeumchulbal.data.local.FavoriteStationDao
import io.github.klanary.jigeumchulbal.data.local.FavoriteStationEntity
import io.github.klanary.jigeumchulbal.data.local.RouteUsageEntity
import io.github.klanary.jigeumchulbal.data.model.Endpoint
import io.github.klanary.jigeumchulbal.data.model.EndpointKind
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.home.sameEndpoints
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** 즐겨찾는 역과 자주 가는 경로(스펙 7.2). */
interface FavoriteRepository {
    val stations: Flow<List<FavoriteStationEntity>>

    fun isFavorite(stationId: String): Flow<Boolean>

    suspend fun toggleStation(stationId: String)

    val routes: Flow<List<FavoriteRouteEntity>>

    fun usagesSince(sinceMillis: Long): Flow<List<RouteUsageEntity>>

    suspend fun addRoute(origin: Endpoint, via: Endpoint?, dest: Endpoint)

    suspend fun removeRoute(id: Long)

    suspend fun findRoute(origin: Endpoint, via: Endpoint?, dest: Endpoint): FavoriteRouteEntity?

    /** 경로 결과를 불러올 때, 출발·경유·도착이 즐겨찾기와 같으면 사용 1회로 기록한다(스펙 7.2 부록 B). */
    suspend fun recordUsageIfFavorite(origin: Endpoint, via: Endpoint?, dest: Endpoint, at: Long)
}

/**
 * 즐겨찾기 행으로 바꾼다. "현재 위치"는 저장하는 순간의 좌표로 고정되므로
 * 나중에 홈 카드에서 지금 위치로 오해하지 않게 [Copy.SAVED_LOCATION]으로 적는다.
 */
fun favoriteRouteOf(origin: Endpoint, via: Endpoint?, dest: Endpoint, createdAt: Long): FavoriteRouteEntity {
    fun label(e: Endpoint) = if (e.kind == EndpointKind.CURRENT_LOCATION) Copy.SAVED_LOCATION else e.label
    return FavoriteRouteEntity(
        originLabel = label(origin), originLat = origin.point.lat, originLng = origin.point.lng,
        viaLabel = via?.let(::label), viaLat = via?.point?.lat, viaLng = via?.point?.lng,
        destLabel = label(dest), destLat = dest.point.lat, destLng = dest.point.lng,
        createdAt = createdAt,
    )
}

class DefaultFavoriteRepository(
    private val stationDao: FavoriteStationDao,
    private val routeDao: FavoriteRouteDao,
    private val now: () -> Long = System::currentTimeMillis,
) : FavoriteRepository {
    override val stations: Flow<List<FavoriteStationEntity>> = stationDao.observeAll()

    override fun isFavorite(stationId: String): Flow<Boolean> = stationDao.observeIsFavorite(stationId)

    override suspend fun toggleStation(stationId: String) {
        if (stationDao.observeIsFavorite(stationId).first()) {
            stationDao.delete(stationId)
        } else {
            stationDao.insert(FavoriteStationEntity(stationId = stationId, createdAt = now()))
        }
    }

    override val routes: Flow<List<FavoriteRouteEntity>> = routeDao.observeAll()

    override fun usagesSince(sinceMillis: Long): Flow<List<RouteUsageEntity>> = routeDao.observeUsagesSince(sinceMillis)

    override suspend fun addRoute(origin: Endpoint, via: Endpoint?, dest: Endpoint) {
        if (findRoute(origin, via, dest) == null) routeDao.insert(favoriteRouteOf(origin, via, dest, now()))
    }

    override suspend fun removeRoute(id: Long) = routeDao.delete(id)

    override suspend fun findRoute(origin: Endpoint, via: Endpoint?, dest: Endpoint): FavoriteRouteEntity? =
        routeDao.observeAll().first().find { sameEndpoints(it, origin, via, dest) }

    override suspend fun recordUsageIfFavorite(origin: Endpoint, via: Endpoint?, dest: Endpoint, at: Long) {
        findRoute(origin, via, dest)?.let { routeDao.insertUsage(RouteUsageEntity(routeId = it.id, usedAt = at)) }
    }
}
