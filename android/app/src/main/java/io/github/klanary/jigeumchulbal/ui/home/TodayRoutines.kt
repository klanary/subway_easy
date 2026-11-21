package io.github.klanary.jigeumchulbal.ui.home

import io.github.klanary.jigeumchulbal.routine.RoutineEntity
import io.github.klanary.jigeumchulbal.routine.dayBit
import io.github.klanary.jigeumchulbal.ui.RouteFlowGraph
import java.time.DayOfWeek

/**
 * 홈 아래 패널에 보일 오늘 루틴(2차 다듬기 스펙 4.2): 켜져 있고 오늘 요일이며 아직 지나지 않은 루틴을
 * 시각 순으로 최대 [limit]개. 관리는 루틴 목록 화면에서 한다.
 */
fun todayRoutines(routines: List<RoutineEntity>, nowMinute: Int, today: DayOfWeek, limit: Int = 2): List<RoutineEntity> =
    routines.filter { it.enabled && it.days and dayBit(today) != 0 && it.minuteOfDay > nowMinute }
        .sortedBy { it.minuteOfDay }
        .take(limit)

/** 오늘 루틴 줄을 누르면 그 경로의 결과를 바로 연다(사용자 결정, 자주 가는 경로와 같은 동작). */
fun RoutineEntity.toRouteGraph(): RouteFlowGraph =
    RouteFlowGraph(fromId = fromId, fromName = fromName, viaId = viaId, viaName = viaName, toId = toId, toName = toName)
