package io.github.klanary.jigeumchulbal.data.repository

import io.github.klanary.jigeumchulbal.data.local.FavoriteStationDao
import io.github.klanary.jigeumchulbal.data.local.FavoriteStationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** 즐겨찾는 역. 자주 가는 경로 함수는 P4가 더한다. */
interface FavoriteRepository {
    val stations: Flow<List<FavoriteStationEntity>>

    fun isFavorite(stationId: String): Flow<Boolean>

    suspend fun toggleStation(stationId: String)
}

class DefaultFavoriteRepository(
    private val stationDao: FavoriteStationDao,
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
}
