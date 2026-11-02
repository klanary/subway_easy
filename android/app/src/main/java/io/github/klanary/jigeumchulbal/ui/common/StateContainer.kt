package io.github.klanary.jigeumchulbal.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** [UiState]의 다섯 상태를 그린다. 내용만 [content]가 그리고 나머지는 공통 모양을 쓴다. */
@Composable
fun <T> StateContainer(
    state: UiState<T>,
    onRetry: () -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        UiState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        UiState.Empty -> CenteredMessage(emptyText, onRetry = null, modifier = modifier)
        is UiState.Error -> CenteredMessage(state.message, onRetry, modifier)
        UiState.Offline -> CenteredMessage(Copy.OFFLINE, onRetry, modifier)
        is UiState.Content -> Column(modifier) {
            if (state.stale) {
                AssistChip(
                    onClick = onRetry,
                    label = { Text(Copy.STALE) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Box(Modifier.alpha(if (state.stale) 0.5f else 1f)) {
                content(state.data)
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text(Copy.RETRY)
            }
        }
    }
}
