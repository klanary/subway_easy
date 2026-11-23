package io.github.klanary.jigeumchulbal.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MyPlaceDao {
    /** 집, 학교, 회사, 직접 추가 순서. 같은 종류는 추가한 순서. */
    @Query(
        """
        SELECT * FROM my_places
        ORDER BY CASE type WHEN 'HOME' THEN 0 WHEN 'SCHOOL' THEN 1 WHEN 'WORK' THEN 2 ELSE 3 END, id
        """,
    )
    abstract fun observeAll(): Flow<List<MyPlaceEntity>>

    /** 집·학교·회사는 하나씩만 둔다: 같은 종류를 지우고 넣는다. 직접 추가(CUSTOM)는 쌓인다. */
    @Transaction
    open suspend fun saveTyped(place: MyPlaceEntity) {
        if (place.type != MyPlaceType.CUSTOM) deleteByType(place.type)
        insert(place)
    }

    @Query("DELETE FROM my_places WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT * FROM my_places WHERE id = :id")
    abstract suspend fun get(id: Long): MyPlaceEntity?

    @Insert
    abstract suspend fun insert(place: MyPlaceEntity): Long

    @Query("DELETE FROM my_places WHERE type = :type")
    abstract suspend fun deleteByType(type: MyPlaceType)
}
