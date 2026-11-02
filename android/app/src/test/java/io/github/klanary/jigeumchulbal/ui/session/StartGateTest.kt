package io.github.klanary.jigeumchulbal.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

class StartGateTest {
    @Test fun firstTimeShowsIntro() = assertEquals(StartDecision.SHOW_INTRO, decideStart(introShown = false, notificationsGranted = true))

    @Test fun grantedStartsNormally() = assertEquals(StartDecision.START_NORMAL, decideStart(true, true))

    @Test fun deniedRunsInAppOnly() = assertEquals(StartDecision.START_IN_APP_ONLY, decideStart(true, false))
}
