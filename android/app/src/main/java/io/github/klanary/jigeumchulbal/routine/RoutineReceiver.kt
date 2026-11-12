package io.github.klanary.jigeumchulbal.routine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.klanary.jigeumchulbal.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 루틴 알람이 울리면 그 루틴을 실행한다(RoutineRunner). 정확한 알람으로 깨어나 세션 서비스를 시작할 수 있다. */
class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ROUTINE_FIRE) return
        val id = intent.getLongExtra(EXTRA_ROUTINE_ID, -1)
        if (id < 0) return
        val runner = (context.applicationContext as App).container.routineRunner
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                runner.run(id)
            } finally {
                pending.finish()
            }
        }
    }
}
