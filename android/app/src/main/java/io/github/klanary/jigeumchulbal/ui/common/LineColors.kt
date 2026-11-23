package io.github.klanary.jigeumchulbal.ui.common

import io.github.klanary.jigeumchulbal.data.remote.StepDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode

// 호선 색 표(스펙 7.3). 서버가 정규화한 호선 이름(P2 normalize_line)을 키로 쓴다.

const val WALK_COLOR = 0xFF9E9E9E
const val BUS_COLOR = 0xFF33A23D
private const val UNKNOWN_LINE_COLOR = 0xFF888888

private val LINE_COLORS: Map<String, Long> = mapOf(
    "1호선" to 0xFF0052A4,
    "2호선" to 0xFF00A84D,
    "3호선" to 0xFFEF7C1C,
    "4호선" to 0xFF00A5DE,
    "5호선" to 0xFF996CAC,
    "6호선" to 0xFFCD7C2F,
    "7호선" to 0xFF747F00,
    "8호선" to 0xFFE6186C,
    "9호선" to 0xFFBDB092,
    "경의중앙선" to 0xFF77C4A3,
    "공항철도" to 0xFF0090D2,
    "경춘선" to 0xFF0C8E72,
    "수인분당선" to 0xFFFABE00,
    "신분당선" to 0xFFD4003B,
    "우이신설선" to 0xFFB0CE18,
    "서해선" to 0xFF8FC31F,
    "신림선" to 0xFF6789CA,
    "경강선" to 0xFF0054A6,
    "GTX-A" to 0xFF9A6292,
)

fun lineColor(lineName: String?): Long = LINE_COLORS[lineName] ?: UNKNOWN_LINE_COLOR

/** 단계의 표시 색: 도보는 회색, 지하철은 호선 색, 버스는 초록. */
fun StepDto.color(): Long = when (mode) {
    StepMode.WALK -> WALK_COLOR
    StepMode.SUBWAY -> lineColor(lineName)
    StepMode.BUS -> BUS_COLOR
}
