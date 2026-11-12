package io.github.klanary.jigeumchulbal.data.repository

import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteDao
import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteEntity
import io.github.klanary.jigeumchulbal.data.local.RouteUsageEntity
import io.github.klanary.jigeumchulbal.data.model.StationRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** 자주 가는 경로(재설계 스펙 4.7, D10). 출발·경유·도착 역 id가 같으면 같은 경로다. */
interface FavoriteRepository {
    val routes: Flow<List<FavoriteRouteEntity>>

    fun usagesSince(sinceMillis: Long): Flow<List<RouteUsageEntity>>

    suspend fun add(from: StationRef, via: StationRef?, to: StationRef)

    suspend fun remove(id: Long)

    suspend fun find(from: StationRef, via: StationRef?, to: StationRef): FavoriteRouteEntity?

    /** 경로 결과를 불러올 때, 출발·경유·도착이 즐겨찾기와 같으면 사용 1회로 기록한다(스펙 7.2 부록 B). */
    suspend fun recordUsageIfFavorite(from: StationRef, via: StationRef?, to: StationRef, at: Long)
}

fun FavoriteRouteEntity.sameStations(from: StationRef, via: StationRef?, to: StationRef): Boolean =
    fromId == from.id && viaId == via?.id && toId == to.id

val FavoriteRouteEntity.from get() = StationRef(fromId, fromName)
val FavoriteRouteEntity.to get() = StationRef(toId, toName)
val FavoriteRouteEntity.via get() = viaId?.let { StationRef(it, viaName ?: it) }

class DefaultFavoriteRepository(
    private val routeDao: FavoriteRouteDao,
    private val now: () -> Long = System::currentTimeMillis,
) : FavoriteRepository {
    override val routes: Flow<List<FavoriteRouteEntity>> = routeDao.observeAll()

    override fun usagesSince(sinceMillis: Long): Flow<List<RouteUsageEntity>> = routeDao.observeUsagesSince(sinceMillis)

    override suspend fun add(from: StationRef, via: StationRef?, to: StationRef) {
        if (find(from, via, to) != null) return
        routeDao.insert(
            FavoriteRouteEntity(
                fromId = from.id, fromName = from.name,
                viaId = via?.id, viaName = via?.name,
                toId = to.id, toName = to.name,
                createdAt = now(),
            ),
        )
    }

    override suspend fun remove(id: Long) = routeDao.delete(id)

    override suspend fun find(from: StationRef, via: StationRef?, to: StationRef): FavoriteRouteEntity? =
        routeDao.observeAll().first().find { it.sameStations(from, via, to) }

    override suspend fun recordUsageIfFavorite(from: StationRef, via: StationRef?, to: StationRef, at: Long) {
        find(from, via, to)?.let { routeDao.insertUsage(RouteUsageEntity(routeId = it.id, usedAt = at)) }
    }
}
