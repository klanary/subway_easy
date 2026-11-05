package io.github.klanary.jigeumchulbal.ui.routedetail

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.LineTrainsDto
import io.github.klanary.jigeumchulbal.fakes.FakeAlarmScheduler
import io.github.klanary.jigeumchulbal.fakes.FakeLauncher
import io.github.klanary.jigeumchulbal.fakes.FakeLocationProvider
import io.github.klanary.jigeumchulbal.fakes.FakeNotifier
import io.github.klanary.jigeumchulbal.fakes.FakeStationRepository
import io.github.klanary.jigeumchulbal.fakes.FakeTrainRepository
import io.github.klanary.jigeumchulbal.fakes.testSessionStore
import io.github.klanary.jigeumchulbal.fixtures.specRoute
import io.github.klanary.jigeumchulbal.session.BOARD
import io.github.klanary.jigeumchulbal.session.DetectedTrain
import io.github.klanary.jigeumchulbal.session.FAR
import io.github.klanary.jigeumchulbal.session.GWANGHWAMUN
import io.github.klanary.jigeumchulbal.session.SEODAEMUN
import io.github.klanary.jigeumchulbal.session.SessionController
import io.github.klanary.jigeumchulbal.session.SessionEvent
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.session.TrainSource
import io.github.klanary.jigeumchulbal.session.fix
import io.github.klanary.jigeumchulbal.session.line5to6Route
import io.github.klanary.jigeumchulbal.session.nearTrain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 경로 상세만 보던 중 탑승을 알아내면 화면 안에서 묻기(위치 결합 스펙 7.1). */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardingPromptViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val location = FakeLocationProvider(permission = true, point = null).apply { fixes = flowOf(fix(BOARD, 0), fix(FAR, 40)) }
    private val stations = FakeStationRepository().apply {
        searchByQuery = mapOf("광화문" to listOf(GWANGHWAMUN), "서대문" to listOf(SEODAEMUN))
    }
    private val trains = FakeTrainRepository().apply {
        nearResult = ApiResult.Ok(LineTrainsDto("5호선", "t", trains = listOf(nearTrain("하남검단산", "서대문"))))
    }
    private val controller = SessionController(testSessionStore(), FakeAlarmScheduler(), FakeNotifier(), FakeLauncher()) { 0L }

    private fun vm() = BoardingPromptViewModel(location, stations, trains, controller)

    @Test fun promptAfterBoardingThenStartIdentifiesTrain() = runTest {
        val vm = vm()
        vm.watch(line5to6Route())
        assertEquals(BoardingPrompt(DetectedTrain("5123", "하남검단산")), vm.prompt.value)
        controller.start(SessionEvent.StartRiding(line5to6Route(), AppSettings()), inAppOnly = false)
        vm.confirmStarted()
        val t = (controller.state.value as SessionState.Riding).tracking!!
        assertEquals("5123" to TrainSource.AUTO, t.trainNo to t.source)
        assertNull(vm.prompt.value)
    }

    @Test fun noWatchWithoutPermissionOrDuringSession() = runTest {
        location.permission = false
        vm().watch(line5to6Route())
        location.permission = true
        controller.start(SessionEvent.StartRiding(specRoute(), AppSettings()), inAppOnly = false)
        vm().watch(line5to6Route())
        assertTrue(trains.nearCalls.isEmpty())
    }

    @Test fun dismissHidesPromptAndStopsAsking() = runTest {
        val vm = vm()
        vm.watch(line5to6Route())
        vm.dismiss()
        vm.watch(line5to6Route())
        assertNull(vm.prompt.value)
        assertEquals(1, trains.nearCalls.size)
    }
}
