package io.github.klanary.jigeumchulbal.session

/** 출발·하차 알림의 제목과 본문(스펙 8.2, 8.3, 8.7). 앱 안 모드의 대화상자도 같은 문구를 쓴다. */
fun alertText(content: AlertContent): Pair<String, String> = when (content) {
    is AlertContent.LeaveNow -> "지금 출발하세요" to trainText(content.minutes, content.destination)
    is AlertContent.TrainSoon -> "열차가 곧 도착해요" to trainText(content.minutes, content.destination)
    AlertContent.Replanned -> "다음 열차 기준으로 다시 안내할게요" to "출발할 시각이 되면 다시 알려드려요"
    is AlertContent.AlightSoon -> "곧 ${stopLabel(content.point)}이에요" to alightText(content.point)
    AlertContent.WaitingInterrupted ->
        "대기 알림이 중단됐어요" to "기기가 다시 켜져 승차 전 알림을 멈췄어요. 필요하면 다시 시작해 주세요"
    AlertContent.RoutineSkipped -> "루틴을 건너뛰었어요" to "진행 중인 통학 알림이 있어 루틴을 건너뛰었어요"
}

private fun trainText(minutes: Int, destination: String?): String =
    if (destination == null) "${minutes}분 뒤 열차" else "${minutes}분 뒤 ${destination}행 열차"

private fun alightText(point: AlightPoint): String = when (point.kind) {
    PointKind.TRANSFER -> point.nextLine?.let { "$it 환승 준비를 하세요" } ?: "환승 준비를 하세요"
    PointKind.VIA, PointKind.FINAL -> "내릴 준비를 하세요"
}
