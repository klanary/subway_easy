package io.github.klanary.jigeumchulbal.routine

import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow

/** 루틴의 다음 실행 시각 하나를 정확한 알람으로 건다(재설계 스펙 5.4). */
interface RoutineAlarmScheduler {
    fun schedule(routineId: Long, atMillis: Long)

    fun cancel(routineId: Long)
}

interface RoutineRepository {
    val routines: Flow<List<RoutineEntity>>

    suspend fun get(id: Long): RoutineEntity?

    /** 새로 만들거나 고친다. 돌려주는 값은 루틴 id. 저장한 뒤 다음 실행을 다시 예약한다. */
    suspend fun save(routine: RoutineEntity): Long

    suspend fun setEnabled(id: Long, enabled: Boolean)

    suspend fun delete(id: Long)

    /** 이 루틴의 다음 실행을 예약한다(켜져 있고 요일이 있으면). 루틴이 울린 뒤에도 부른다. */
    suspend fun reschedule(id: Long)

    /** 재부팅·앱 업데이트·시간 변경 때 켜진 루틴을 모두 다시 예약한다. */
    suspend fun rescheduleAll()
}

class DefaultRoutineRepository(
    private val dao: RoutineDao,
    private val scheduler: RoutineAlarmScheduler,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) : RoutineRepository {
    override val routines: Flow<List<RoutineEntity>> = dao.observeAll()

    override suspend fun get(id: Long): RoutineEntity? = dao.get(id)

    override suspend fun save(routine: RoutineEntity): Long {
        val id = dao.upsert(routine)
        apply(routine.copy(id = id))
        return id
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        dao.setEnabled(id, enabled)
        dao.get(id)?.let(::apply)
    }

    override suspend fun delete(id: Long) {
        scheduler.cancel(id)
        dao.delete(id)
    }

    override suspend fun reschedule(id: Long) {
        dao.get(id)?.let(::apply) ?: scheduler.cancel(id)
    }

    override suspend fun rescheduleAll() {
        dao.all().forEach(::apply)
    }

    private fun apply(routine: RoutineEntity) {
        val next = if (routine.enabled) nextOccurrence(routine.days, routine.minuteOfDay, now()) else null
        if (next == null) scheduler.cancel(routine.id) else scheduler.schedule(routine.id, next.toInstant().toEpochMilli())
    }
}
