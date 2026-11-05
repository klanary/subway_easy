package io.github.klanary.jigeumchulbal.ui.session

import io.github.klanary.jigeumchulbal.data.remote.ApiError
import io.github.klanary.jigeumchulbal.data.remote.TrainNearDto
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.ui.common.Copy

// 탄 열차 직접 고르기(⑭ 3번, 위치 결합 스펙 7.1)와 세션 화면의 추적 상태 줄.

/** 목록 한 줄: "OO행 · 지금 XX역". 역 이름에는 "역"을 한 번만 붙인다. */
fun trainChoiceLabel(train: TrainNearDto): String {
    val station = if (train.stationName.endsWith("역")) train.stationName else "${train.stationName}역"
    val destination = train.destination?.let { "${it}행" } ?: "열차"
    return "$destination · 지금 $station"
}

/** 탑승 중 추적 상태. 추적하지 않는 세션이면 null이다. */
fun trackingStatus(state: SessionState.Riding): String? {
    val tracking = state.tracking ?: return null
    return when {
        tracking.trainNo == null -> Copy.PICK_TRAIN_HINT
        tracking.lost -> Copy.TRACKING_LOST
        else -> "${tracking.destination?.let { "${it}행 열차" } ?: "열차"} 실시간 추적 중"
    }
}

/** 직접 고르기 목록 대화상자의 상태. */
sealed interface PickerState {
    data object Hidden : PickerState

    data object Loading : PickerState

    data class Loaded(val trains: List<TrainNearDto>) : PickerState

    data class Failed(val message: String) : PickerState
}

/** 목록을 못 불러왔을 때의 문구. 다른 화면(UiState)과 같은 기준이다. */
internal fun pickerErrorMessage(error: ApiError): String = when (error) {
    ApiError.Offline -> Copy.OFFLINE
    ApiError.Network -> Copy.NETWORK_ERROR
    is ApiError.Server -> error.message
    is ApiError.Unexpected -> Copy.UNEXPECTED_ERROR
}
