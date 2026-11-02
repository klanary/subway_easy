package io.github.klanary.jigeumchulbal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.klanary.jigeumchulbal.session.ACTION_OPEN_SESSION
import io.github.klanary.jigeumchulbal.ui.AppNavHost
import io.github.klanary.jigeumchulbal.ui.theme.AppTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    /** 알림·알람 아이콘으로 열린 요청. 화면 이동은 AppNavHost가 한다. */
    private val openSessionRequests = Channel<Unit>(Channel.CONFLATED)
    private val openSessionFlow = openSessionRequests.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 화면 회전·프로세스 복원 때는 내비게이션 상태가 이미 복원되므로 다시 이동하지 않는다.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppTheme {
                AppNavHost(openSessionRequests = openSessionFlow)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_SESSION) openSessionRequests.trySend(Unit)
    }
}
