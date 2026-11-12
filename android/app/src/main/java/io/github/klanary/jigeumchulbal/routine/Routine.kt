package io.github.klanary.jigeumchulbal.routine

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import io.github.klanary.jigeumchulbal.data.model.StationRef
import kotlinx.coroutines.flow.Flow

/**
 * 루틴(재설계 스펙 5.4): 요일·시각마다 그 경로로 "지금 출발하세요" 알림 세션을 시작한다. 기기에만 저장한다.
 * [routeJson]은 고른 경로(좌표를 뺀 RouteDto JSON), [days]는 요일 비트([dayBit]), [minuteOfDay]는 0~1439분.
 */
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val fromId: String,
    val fromName: String,
    val viaId: String?,
    val viaName: String?,
    val toId: String,
    val toName: String,
    val routeJson: String,
    val days: Int,
    val minuteOfDay: Int,
    val walkMin: Int = DEFAULT_WALK_MIN,
    val alightAlerts: Boolean = false,
    val enabled: Boolean = true,
) {
    val from get() = StationRef(fromId, fromName)
    val to get() = StationRef(toId, toName)
    val via get() = viaId?.let { StationRef(it, viaName ?: it) }

    /** "마포 → 공덕" */
    val title get() = listOfNotNull(fromName, viaName, toName).joinToString(" → ")

    companion object {
        const val DEFAULT_WALK_MIN = 5
        const val MAX_WALK_MIN = 30
    }
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY minuteOfDay, id")
    fun observeAll(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines ORDER BY minuteOfDay, id")
    suspend fun all(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun get(id: Long): RoutineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(routine: RoutineEntity): Long

    @Query("UPDATE routines SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun delete(id: Long)
}

/** 경로 상세에서 [루틴으로 만들기]를 누를 때의 출발·경유·도착과 고른 경로(좌표를 뺀 JSON). */
data class RoutineDraft(
    val from: StationRef,
    val via: StationRef?,
    val to: StationRef,
    val routeJson: String,
)
