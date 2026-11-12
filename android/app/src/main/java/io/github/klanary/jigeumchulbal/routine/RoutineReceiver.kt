package io.github.klanary.jigeumchulbal.routine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 루틴 알람이 울리면 받는다. 세션 시작은 A10의 RoutineRunner가 한다. */
class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
