package io.github.klanary.jigeumchulbal.ui.common

import io.github.klanary.jigeumchulbal.data.remote.ApiError
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Test

class UiStateTest {
    @Test fun okEmptyIsEmpty() =
        assertEquals(UiState.Empty, ApiResult.Ok(emptyList<Int>()).toUiState(isEmpty = { it.isEmpty() }))

    @Test fun okStaleIsStaleContent() =
        assertEquals(UiState.Content(listOf(1), stale = true), ApiResult.Ok(listOf(1)).toUiState(isStale = { true }))

    @Test fun offlineMapsToOffline() =
        assertEquals(UiState.Offline, ApiResult.Err(ApiError.Offline).toUiState<Int>())

    @Test fun serverMessageShownAsIs() =
        assertEquals(
            UiState.Error("대중교통 경로를 찾지 못했어요."),
            ApiResult.Err(ApiError.Server("NO_ROUTE", "NO_RESULTS", "대중교통 경로를 찾지 못했어요.")).toUiState<Int>(),
        )

    @Test fun networkErrorUsesCopy() =
        assertEquals(UiState.Error(Copy.NETWORK_ERROR), ApiResult.Err(ApiError.Network).toUiState<Int>())
}
