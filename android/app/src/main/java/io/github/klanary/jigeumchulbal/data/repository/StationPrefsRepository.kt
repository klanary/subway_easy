package io.github.klanary.jigeumchulbal.data.repository

import io.github.klanary.jigeumchulbal.data.local.FavoriteStationDao
import io.github.klanary.jigeumchulbal.data.local.FavoriteStationEntity
import io.github.klanary.jigeumchulbal.data.local.RecentRouteDao
import io.github.klanary.jigeumchulbal.data.local.RecentRouteEntity
import io.github.klanary.jigeumchulbal.data.model.StationRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** 즐겨찾는 역(수동 검토 2026-11-22). 역 시트의 별과 역 검색 맨 위 목록. */
interface FavoriteStationRepository {
    val favorites: Flow<List<FavoriteStationEntity>>

    /** 있으면 빼고, 없으면 넣는다. */
    suspend fun toggle(station: StationRef)
}

class DefaultFavoriteStationRepository(
    private val dao: FavoriteStationDao,
    private val now: () -> Long = System::currentTimeMillis,
) : FavoriteStationRepository {
    override val favorites: Flow<List<FavoriteStationEntity>> = dao.observeAll()

    override suspend fun toggle(station: StationRef) {
        if (favorites.first().any { it.stationId == station.id }) {
            dao.remove(station.id)
        } else {
            dao.add(FavoriteStationEntity(station.id, station.name, now()))
        }
    }
}

/** 최근 찾아본 경로(수동 검토 2026-11-22). 경로를 찾을 때마다 남기고, 역 검색의 [최근 경로] 탭에 보인다. */
interface RecentRouteRepository {
    val recent: Flow<List<RecentRouteEntity>>

    suspend fun record(from: StationRef, via: StationRef?, to: StationRef)
}

class DefaultRecentRouteRepository(
    private val dao: RecentRouteDao,
    private val now: () -> Long = System::currentTimeMillis,
) : RecentRouteRepository {
    override val recent: Flow<List<RecentRouteEntity>> = dao.observeRecent()

    override suspend fun record(from: StationRef, via: StationRef?, to: StationRef) {
        dao.record(
            RecentRouteEntity(
                fromId = from.id, fromName = from.name,
                viaId = via?.id.orEmpty(), viaName = via?.name.orEmpty(),
                toId = to.id, toName = to.name, usedAt = now(),
            ),
        )
    }
}
