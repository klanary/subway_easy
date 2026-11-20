package io.github.klanary.jigeumchulbal.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** [UiState]의 다섯 상태를 그린다. 내용만 [content]가 그리고 나머지는 공통 조각([StateMessage]·[StateLoading]·[StaleNotice])을 쓴다. */
@Composable
fun <T> StateContainer(
    state: UiState<T>,
    onRetry: () -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier,
    size: StateSize = StateSize.Screen,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        UiState.Loading -> StateLoading(size, modifier)
        UiState.Empty -> StateMessage(StateKind.Empty, emptyText, size, onRetry = null, modifier = modifier)
        is UiState.Error -> StateMessage(StateKind.Error, state.message, size, onRetry, modifier)
        UiState.Offline -> StateMessage(StateKind.Offline, Copy.OFFLINE, size, onRetry, modifier)
        is UiState.Content -> Column(modifier) {
            if (state.stale) StaleNotice(onRetry, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            content(state.data)
        }
    }
}
