package io.github.klanary.jigeumchulbal.session

import android.content.BroadcastReceiver
import android.content.Context
import io.github.klanary.jigeumchulbal.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 수신기에서 세션 컨트롤러로 일을 넘긴다. 앱 프로세스가 죽어 있었으면 시스템이 앱을 다시 띄우고,
 * 컨트롤러가 저장된 세션을 먼저 불러온 뒤 처리한다(SessionController.ensureLoaded).
 */
internal fun BroadcastReceiver.runWithSession(context: Context, block: suspend (SessionController) -> Unit) {
    val controller = (context.applicationContext as App).container.sessionController
    val pending = goAsync() // 수신기가 끝난 뒤에도 몇 초 동안 작업을 이어 갈 수 있게 한다
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            block(controller)
        } finally {
            pending.finish()
        }
    }
}
