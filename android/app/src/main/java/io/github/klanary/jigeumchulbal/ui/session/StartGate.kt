package io.github.klanary.jigeumchulbal.ui.session

/** 세션을 어떻게 시작할지(스펙 4.4, 8.6). */
enum class StartDecision { SHOW_INTRO, START_NORMAL, START_IN_APP_ONLY }

/**
 * 처음이면 권한 안내부터 보인다. 그 뒤로는 알림이 허용돼 있으면 보통 세션, 아니면 앱 안 모드로 시작하고
 * 다시 조르지 않는다(설정 화면의 [설정 열기]로 바꿀 수 있다).
 */
fun decideStart(introShown: Boolean, notificationsGranted: Boolean): StartDecision = when {
    !introShown -> StartDecision.SHOW_INTRO
    notificationsGranted -> StartDecision.START_NORMAL
    else -> StartDecision.START_IN_APP_ONLY
}
