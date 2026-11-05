package io.github.klanary.jigeumchulbal.tracking

import io.github.klanary.jigeumchulbal.session.AlightPoint
import io.github.klanary.jigeumchulbal.session.Tracking

/**
 * 실시간 위치로 탄 열차를 따라갈 수 있는 호선(위치 결합 스펙 머리말, 3.2). 서버 노선 표(`line_topology.json`)의 호선과
 * 같게 둔다. 서버에 호선을 더하면 여기에도 더한다.
 */
val TRACKED_LINES: Set<String> = setOf("5호선", "6호선")

/** 이 지점(지하철 단계의 하차 지점)의 열차를 따라갈 수 있는지. 노선 표에 없는 호선의 단계는 추적하지 않는다(7.2). */
fun AlightPoint.isTrackable(): Boolean = !isBus && line in TRACKED_LINES && alightStationId != null

/** [from] 번째 지점부터 처음으로 따라갈 수 있는 지점의 추적(열차는 아직 정하지 않음). 없으면 null. */
fun trackingFrom(points: List<AlightPoint>, from: Int): Tracking? =
    (from.coerceAtLeast(0) until points.size).firstNotNullOfOrNull { index ->
        val point = points[index]
        val line = point.line
        val alight = point.alightStationId
        if (point.isTrackable() && line != null && alight != null) Tracking(index, line, alight) else null
    }
