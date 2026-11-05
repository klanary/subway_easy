package io.github.klanary.jigeumchulbal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import io.github.klanary.jigeumchulbal.session.ACTION_OPEN_SESSION
import io.github.klanary.jigeumchulbal.session.ACTION_PICK_TRAIN
import io.github.klanary.jigeumchulbal.ui.AppNavHost
import io.github.klanary.jigeumchulbal.ui.theme.AppTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** 알림·알람 아이콘으로 열린 요청(true면 탄 열차 고르기까지). 화면 이동은 AppNavHost가 한다. */
    private val openSessionRequests = Channel<Boolean>(Channel.CONFLATED)
    private val openSessionFlow = openSessionRequests.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 저장된 세션을 이어 간다(스펙 8.7). 프로세스가 죽었다가 화면 상태만 복원된 경우도 있어 매번 부른다.
        // 같은 알람은 같은 요청 코드로 덮어쓰고, 이미 도는 대기 서비스는 조회를 새로 시작하지 않는다.
        lifecycleScope.launch { (application as App).container.sessionController.restore(onBoot = false) }
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
        when (intent?.action) {
            ACTION_OPEN_SESSION -> openSessionRequests.trySend(false)
            ACTION_PICK_TRAIN -> openSessionRequests.trySend(true)
        }
    }
}
