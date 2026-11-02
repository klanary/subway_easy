package io.github.klanary.jigeumchulbal.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteRouteDao {
    @Query("SELECT * FROM favorite_routes ORDER BY createdAt")
    fun observeAll(): Flow<List<FavoriteRouteEntity>>

    @Insert
    suspend fun insert(route: FavoriteRouteEntity): Long

    /** 사용 기록(route_usages)은 외래 키 CASCADE로 함께 지워진다. */
    @Query("DELETE FROM favorite_routes WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertUsage(usage: RouteUsageEntity)

    @Query("SELECT * FROM route_usages WHERE usedAt >= :sinceMillis")
    suspend fun usagesSince(sinceMillis: Long): List<RouteUsageEntity>

    @Query("SELECT * FROM route_usages WHERE usedAt >= :sinceMillis")
    fun observeUsagesSince(sinceMillis: Long): Flow<List<RouteUsageEntity>>
}
