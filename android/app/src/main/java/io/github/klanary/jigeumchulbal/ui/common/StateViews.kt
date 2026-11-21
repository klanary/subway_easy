package io.github.klanary.jigeumchulbal.ui.common

import androidx.compose.foundation.layout.heightIn
import io.github.klanary.jigeumchulbal.ui.theme.ButtonShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 상태 표시의 크기. [Screen]은 화면을 채워 가운데, [Inline]은 시트·목록 안에서 내용 높이만 차지한다. */
enum class StateSize { Screen, Inline }

/** 내용이 아닌 상태의 종류. 오류·오프라인만 다시 시도할 수 있다. */
enum class StateKind { Empty, Error, Offline }

private val StateKind.icon: ImageVector
    get() = when (this) {
        StateKind.Empty -> Icons.Filled.Search
        StateKind.Error -> Icons.Filled.Warning
        StateKind.Offline -> Icons.Filled.Info
    }

/** 전체 화면용 제목. 없으면 [text]를 제목처럼 쓴다. */
private val StateKind.title: String?
    get() = when (this) {
        StateKind.Empty -> null
        StateKind.Error -> Copy.ERROR_TITLE
        StateKind.Offline -> Copy.OFFLINE_TITLE
    }

/**
 * 빈 화면·오류·오프라인 안내(화면 다듬기 Task 2 합의: 상태별 아이콘 + 제목 + 설명 + 채운 버튼, 인라인은 한 줄 + 오른쪽 글자 버튼).
 * [StateKind.Empty]는 [onRetry]가 있어도 버튼을 그리지 않는다.
 */
@Composable
fun StateMessage(kind: StateKind, text: String, size: StateSize, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val retry = onRetry.takeIf { kind != StateKind.Empty }
    val tint = if (kind == StateKind.Empty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
    when (size) {
        StateSize.Screen -> Column(
            modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(kind.icon, contentDescription = null, tint = tint, modifier = Modifier.size(48.dp))
            Spacer(Modifier.size(16.dp))
            val title = kind.title
            Text(title ?: text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            if (title != null) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (retry != null) {
                Spacer(Modifier.size(24.dp))
                Button(onClick = retry, shape = ButtonShape) { Text(Copy.RETRY) }
            }
        }
        StateSize.Inline -> Row(
            modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(kind.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (retry != null) TextButton(onClick = retry, shape = ButtonShape) { Text(Copy.RETRY) }
        }
    }
}

/** 불러오는 중. 전체 화면은 가운데 원형(아래에 [text]), 인라인은 얇은 막대. 화면 읽기 프로그램에는 [Copy.LOADING]으로 읽힌다. */
@Composable
fun StateLoading(size: StateSize, modifier: Modifier = Modifier, text: String? = null) {
    val described = Modifier.semantics { contentDescription = Copy.LOADING }
    when (size) {
        StateSize.Screen -> Column(
            modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(described)
            if (text != null) {
                Spacer(Modifier.size(16.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        StateSize.Inline -> LinearProgressIndicator(modifier.fillMaxWidth().padding(vertical = 8.dp).then(described))
    }
}

/** 오래된 정보 띠(Task 2 합의: 내용은 흐리지 않고 위에 색 띠, 누르면 다시 불러온다). */
@Composable
fun StaleNotice(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        // 누르기 쉬운 높이(48dp 이상, 2차 다듬기 Task 4)
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.small)
            .clickable(onClick = onRetry)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = MaterialTheme.colorScheme.onTertiaryContainer
        Icon(Icons.Filled.Refresh, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(Copy.STALE, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
        Text(Copy.RETRY, style = MaterialTheme.typography.labelLarge, color = color)
    }
}
