package io.github.klanary.jigeumchulbal.data.repository

import io.github.klanary.jigeumchulbal.data.local.RecentSearchDao
import io.github.klanary.jigeumchulbal.data.local.RecentSearchEntity
import io.github.klanary.jigeumchulbal.data.model.StationRef
import kotlinx.coroutines.flow.Flow

/** 최근 검색한 역(최대 20개, 재설계 스펙 4.3). */
interface RecentSearchRepository {
    val recent: Flow<List<RecentSearchEntity>>

    suspend fun record(station: StationRef)
}

class DefaultRecentSearchRepository(
    private val dao: RecentSearchDao,
    private val now: () -> Long = System::currentTimeMillis,
) : RecentSearchRepository {
    override val recent: Flow<List<RecentSearchEntity>> = dao.observeRecent()

    override suspend fun record(station: StationRef) {
        dao.record(RecentSearchEntity(stationId = station.id, name = station.name, usedAt = now()))
    }
}
