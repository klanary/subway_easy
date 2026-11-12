package io.github.klanary.jigeumchulbal.ui

import kotlinx.serialization.Serializable

// 화면 목적지(Navigation Compose 타입 안전 경로).

@Serializable
object HomeDest

/** 경로 편집 → 경로 결과 → 경로 상세가 함께 쓰는 중첩 그래프. [favoriteRouteId]면 그 경로로 바로 검색한다. */
@Serializable
data class RouteFlowGraph(val favoriteRouteId: Long? = null)

@Serializable
object RouteEditDest

@Serializable
object RoutesDest

@Serializable
data class RouteDetailDest(val routeId: String)

/** 경로 편집의 칸. */
@Serializable
enum class Slot { ORIGIN, VIA, DEST }

/** 역 검색. [slot]이 없으면 고른 역의 상세를 연다. */
@Serializable
data class SearchDest(val slot: Slot? = null)

@Serializable
data class StationDest(val stationId: String)

@Serializable
object SettingsDest

/** 통학 세션 화면. 알림이나 알람 아이콘을 눌러도 열린다(MainActivity). */
@Serializable
object SessionDest
