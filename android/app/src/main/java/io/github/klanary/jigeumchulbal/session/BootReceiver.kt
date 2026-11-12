package io.github.klanary.jigeumchulbal.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.klanary.jigeumchulbal.App

/**
 * 재부팅되면 알람이 모두 사라진다. 탑승 단계는 남은 지점의 알람을 다시 걸고, 대기 단계는 부팅 직후 포그라운드 서비스
 * 제약 때문에 이어 가지 않고 "대기 알림이 중단됐어요"만 알린다(스펙 8.7).
 * 루틴은 재부팅·앱 업데이트·시간·시간대 변경 때 모두 다시 예약한다(재설계 스펙 5.4).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in RESCHEDULE_ACTIONS) return
        val routines = (context.applicationContext as App).container.routineRepository
        runWithSession(context) { controller ->
            if (action == Intent.ACTION_BOOT_COMPLETED) controller.restore(onBoot = true)
            routines.rescheduleAll()
        }
    }

    private companion object {
        val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
