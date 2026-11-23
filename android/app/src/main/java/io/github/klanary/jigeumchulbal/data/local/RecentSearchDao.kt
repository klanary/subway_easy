package io.github.klanary.jigeumchulbal.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 최근 검색은 최대 20개다(스펙 3.1 ②). */
@Dao
abstract class RecentSearchDao {
    @Query("SELECT * FROM recent_searches ORDER BY usedAt DESC LIMIT $MAX_RECENT")
    abstract fun observeRecent(): Flow<List<RecentSearchEntity>>

    /** 같은 항목을 다시 고르면 새로 쌓지 않고 맨 위로 올린다. 20개를 넘는 오래된 행은 지운다. */
    @Transaction
    open suspend fun record(entry: RecentSearchEntity) {
        upsertByKey(entry.copy(id = 0))
        trimToNewest()
    }

    @Query("SELECT COUNT(*) FROM recent_searches")
    abstract suspend fun count(): Int

    /** dedupeKey가 같은 행이 있으면 지우고 새로 넣는다(유일 색인 충돌 → REPLACE). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertByKey(entry: RecentSearchEntity)

    @Query(
        """
        DELETE FROM recent_searches
        WHERE id NOT IN (SELECT id FROM recent_searches ORDER BY usedAt DESC LIMIT $MAX_RECENT)
        """,
    )
    abstract suspend fun trimToNewest()

    companion object {
        const val MAX_RECENT = 20
    }
}
