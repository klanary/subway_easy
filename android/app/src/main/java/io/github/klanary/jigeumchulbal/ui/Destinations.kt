package io.github.klanary.jigeumchulbal.ui

import kotlinx.serialization.Serializable

// 화면 목적지(Navigation Compose 타입 안전 경로).

@Serializable
object HomeDest

/**
 * 경로 편집 → 경로 결과 → 경로 상세가 함께 쓰는 중첩 그래프.
 * [favoriteRouteId]나 노선도에서 고른 역([fromId]·[toId], 선택 [viaId])이 있으면 바로 검색해 결과를 연다.
 */
@Serializable
data class RouteFlowGraph(
    val favoriteRouteId: Long? = null,
    val fromId: String? = null,
    val fromName: String? = null,
    val viaId: String? = null,
    val viaName: String? = null,
    val toId: String? = null,
    val toName: String? = null,
)

@Serializable
object RouteEditDest

@Serializable
object RoutesDest

@Serializable
data class RouteDetailDest(val routeId: String)

/** 경로 편집의 칸. */
@Serializable
enum class Slot { ORIGIN, VIA, DEST }

/** 역 검색. [slot]이 없으면 노선도에서 연 것이다(고른 역으로 노선도가 간다). */
@Serializable
data class SearchDest(val slot: Slot? = null)

@Serializable
object SettingsDest

/** 루틴 등록·편집. [routineId]가 없으면 새 루틴(경로 상세의 [루틴으로 만들기]가 경로를 넘긴다). */
@Serializable
data class RoutineEditDest(val routineId: Long? = null)

/** 통학 세션 화면. 알림이나 알람 아이콘을 눌러도 열린다(MainActivity). */
@Serializable
object SessionDest
