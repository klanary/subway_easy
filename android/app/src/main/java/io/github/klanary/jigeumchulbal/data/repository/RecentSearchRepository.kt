package io.github.klanary.jigeumchulbal.data.repository

import io.github.klanary.jigeumchulbal.data.local.RecentKind
import io.github.klanary.jigeumchulbal.data.local.RecentSearchDao
import io.github.klanary.jigeumchulbal.data.local.RecentSearchEntity
import io.github.klanary.jigeumchulbal.data.local.recentDedupeKey
import io.github.klanary.jigeumchulbal.data.model.Endpoint
import io.github.klanary.jigeumchulbal.data.model.EndpointKind
import kotlinx.coroutines.flow.Flow

interface RecentSearchRepository {
    val recent: Flow<List<RecentSearchEntity>>

    suspend fun record(endpoint: Endpoint)
}

class DefaultRecentSearchRepository(
    private val dao: RecentSearchDao,
    private val now: () -> Long = System::currentTimeMillis,
) : RecentSearchRepository {
    override val recent: Flow<List<RecentSearchEntity>> = dao.observeRecent()

    /** "현재 위치"는 기록하지 않는다. 내 장소는 장소(PLACE)로 기록한다. */
    override suspend fun record(endpoint: Endpoint) {
        val kind = when (endpoint.kind) {
            EndpointKind.CURRENT_LOCATION -> return
            EndpointKind.STATION -> RecentKind.STATION
            EndpointKind.PIN -> RecentKind.PIN
            EndpointKind.PLACE, EndpointKind.MY_PLACE -> RecentKind.PLACE
        }
        val (lat, lng) = endpoint.point
        dao.record(
            RecentSearchEntity(
                kind = kind,
                label = endpoint.label,
                lat = lat,
                lng = lng,
                stationId = endpoint.stationId,
                usedAt = now(),
                dedupeKey = recentDedupeKey(kind, endpoint.label, lat, lng, endpoint.stationId),
            ),
        )
    }
}
