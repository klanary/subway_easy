package io.github.klanary.jigeumchulbal.session

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.klanary.jigeumchulbal.MainActivity

/** 알림·알람 시계 아이콘을 누르면 통학 세션 화면을 연다(스펙 8.5). MainActivity가 이 동작을 보고 세션 화면으로 간다. */
const val ACTION_OPEN_SESSION = "io.github.klanary.jigeumchulbal.OPEN_SESSION"

fun openSessionPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    0,
    Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_SESSION)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)
