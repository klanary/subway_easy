package io.github.klanary.jigeumchulbal.ui.common

import io.github.klanary.jigeumchulbal.data.remote.ApiError
import io.github.klanary.jigeumchulbal.data.remote.ApiResult

/** 모든 데이터 화면의 다섯 가지 상태(스펙 4.5). */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>

    /** [stale]이면 서버가 만료된 캐시로 대신 응답한 것이다. 흐리게 보여주고 "갱신 지연"을 붙인다. */
    data class Content<T>(val data: T, val stale: Boolean = false) : UiState<T>

    data object Empty : UiState<Nothing>

    data class Error(val message: String) : UiState<Nothing>

    data object Offline : UiState<Nothing>
}

/** 내용만 바꾸고 나머지 상태는 그대로 둔다. */
fun <T, R> UiState<T>.mapContent(transform: (T) -> R): UiState<R> = when (this) {
    is UiState.Content -> UiState.Content(transform(data), stale)
    UiState.Loading -> UiState.Loading
    UiState.Empty -> UiState.Empty
    is UiState.Error -> this
    UiState.Offline -> UiState.Offline
}

fun <T> ApiResult<T>.toUiState(
    isEmpty: (T) -> Boolean = { false },
    isStale: (T) -> Boolean = { false },
): UiState<T> = when (this) {
    is ApiResult.Ok -> if (isEmpty(value)) UiState.Empty else UiState.Content(value, isStale(value))
    is ApiResult.Err -> when (val e = error) {
        ApiError.Offline -> UiState.Offline
        ApiError.Network -> UiState.Error(Copy.NETWORK_ERROR)
        is ApiError.Server -> UiState.Error(e.message)
        is ApiError.Unexpected -> UiState.Error(Copy.UNEXPECTED_ERROR)
    }
}
