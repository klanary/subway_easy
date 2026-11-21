package io.github.klanary.jigeumchulbal.ui.linemap

import io.github.klanary.jigeumchulbal.data.model.StationRef
import kotlinx.serialization.Serializable

enum class TripRole { FROM, VIA, TO }

/** 노선도에서 고르는 중인 출발·경유·도착 역(재설계 스펙 4.1, 4.2). */
@Serializable
data class TripDraft(val from: StationRef? = null, val via: StationRef? = null, val to: StationRef? = null) {
    /** 출발·도착이 있고 셋이 서로 다르면 경로를 찾을 수 있다. */
    val ready: Boolean
        get() {
            val ids = listOfNotNull(from?.id, via?.id, to?.id)
            return from != null && to != null && ids.toSet().size == ids.size
        }

    val isEmpty: Boolean get() = from == null && via == null && to == null

    /** 이 역을 이 역할로 정할 수 있는지. 셋은 서로 달라야 한다(경유는 언제든, 수동 검토 2026-11-22). */
    fun canSet(role: TripRole, station: StationRef): Boolean {
        val others = when (role) {
            TripRole.FROM -> listOf(via, to)
            TripRole.VIA -> listOf(from, to)
            TripRole.TO -> listOf(from, via)
        }
        return others.none { it?.id == station.id }
    }

    fun set(role: TripRole, station: StationRef): TripDraft = when (role) {
        TripRole.FROM -> copy(from = station)
        TripRole.VIA -> copy(via = station)
        TripRole.TO -> copy(to = station)
    }

    /**
     * 노선도 위 띠: 할 일을 말한다. "마포에서 출발 · 도착역을 눌러 주세요", 다 고르면 "마포 → 홍대입구 → 공덕".
     * 아무것도 고르지 않았으면 null.
     */
    fun banner(): String? {
        val from = from
        val to = to
        return when {
            from != null && to != null -> listOfNotNull(from.name, via?.name, to.name).joinToString(" → ")
            from != null -> "${from.name}에서 출발 · 도착역을 눌러 주세요"
            to != null -> "${to.name}까지 · 출발역을 눌러 주세요"
            via != null -> "${via.name} 경유 · 출발역을 눌러 주세요"
            else -> null
        }
    }
}
