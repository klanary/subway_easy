package io.github.klanary.jigeumchulbal.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 재부팅되면 알람이 모두 사라진다. 탑승 단계는 남은 지점의 알람을 다시 걸고, 대기 단계는 부팅 직후 포그라운드 서비스
 * 제약 때문에 이어 가지 않고 "대기 알림이 중단됐어요"만 알린다(스펙 8.7).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runWithSession(context) { it.restore(onBoot = true) }
    }
}
