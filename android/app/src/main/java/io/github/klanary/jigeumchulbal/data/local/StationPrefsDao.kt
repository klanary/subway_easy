package io.github.klanary.jigeumchulbal.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** 즐겨찾는 역(수동 검토 2026-11-22). 최근에 넣은 역이 위. */
@Dao
abstract class FavoriteStationDao {
    @Query("SELECT * FROM favorite_stations ORDER BY createdAt DESC")
    abstract fun observeAll(): Flow<List<FavoriteStationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun add(station: FavoriteStationEntity)

    @Query("DELETE FROM favorite_stations WHERE stationId = :stationId")
    abstract suspend fun remove(stationId: String)
}

/** 최근 찾아본 경로(수동 검토 2026-11-22). 최대 20개, 같은 경로는 맨 위로 올린다. */
@Dao
abstract class RecentRouteDao {
    @Query("SELECT * FROM recent_routes ORDER BY usedAt DESC LIMIT $MAX_RECENT")
    abstract fun observeRecent(): Flow<List<RecentRouteEntity>>

    @Transaction
    open suspend fun record(entry: RecentRouteEntity) {
        upsertByKey(entry.copy(id = 0))
        trimToNewest()
    }

    /** 같은 경로(출발·경유·도착 유일 색인)가 있으면 지우고 새로 넣는다. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertByKey(entry: RecentRouteEntity)

    @Query(
        """
        DELETE FROM recent_routes
        WHERE id NOT IN (SELECT id FROM recent_routes ORDER BY usedAt DESC LIMIT $MAX_RECENT)
        """,
    )
    abstract suspend fun trimToNewest()

    companion object {
        const val MAX_RECENT = 20
    }
}

/** 버전 3 → 4: 즐겨찾는 역과 최근 경로 표를 더한다. 루틴·자주 가는 경로는 그대로 둔다. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `favorite_stations` (`stationId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`stationId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recent_routes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`fromId` TEXT NOT NULL, `fromName` TEXT NOT NULL, `viaId` TEXT NOT NULL, `viaName` TEXT NOT NULL, " +
                "`toId` TEXT NOT NULL, `toName` TEXT NOT NULL, `usedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_recent_routes_fromId_viaId_toId` " +
                "ON `recent_routes` (`fromId`, `viaId`, `toId`)",
        )
    }
}
