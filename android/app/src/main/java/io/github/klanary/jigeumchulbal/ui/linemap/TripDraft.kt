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

    /** 이 역을 이 역할로 정할 수 있는지. 경유는 출발·도착이 정해진 뒤에만, 셋은 서로 달라야 한다. */
    fun canSet(role: TripRole, station: StationRef): Boolean {
        if (role == TripRole.VIA && (from == null || to == null)) return false
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

    /** 노선도 위 띠: "출발: 마포 · 도착: (역을 고르세요)". 아무것도 고르지 않았으면 null. */
    fun banner(): String? {
        if (isEmpty) return null
        return listOfNotNull(
            "출발: ${from?.name ?: PICK}",
            via?.let { "경유: ${it.name}" },
            "도착: ${to?.name ?: PICK}",
        ).joinToString(" · ")
    }

    private companion object {
        const val PICK = "(역을 고르세요)"
    }
}
