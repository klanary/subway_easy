package io.github.klanary.jigeumchulbal.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteStationDao {
    @Query("SELECT * FROM favorite_stations ORDER BY createdAt")
    fun observeAll(): Flow<List<FavoriteStationEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_stations WHERE stationId = :stationId)")
    fun observeIsFavorite(stationId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FavoriteStationEntity)

    @Query("DELETE FROM favorite_stations WHERE stationId = :stationId")
    suspend fun delete(stationId: String)
}
