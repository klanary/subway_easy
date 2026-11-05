package io.github.klanary.jigeumchulbal.ui.session

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.data.remote.ApiError
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.LineTrainsDto
import io.github.klanary.jigeumchulbal.fakes.FakeAlarmScheduler
import io.github.klanary.jigeumchulbal.fakes.FakeLauncher
import io.github.klanary.jigeumchulbal.fakes.FakeNotifier
import io.github.klanary.jigeumchulbal.fakes.FakeStationRepository
import io.github.klanary.jigeumchulbal.fakes.FakeTrainRepository
import io.github.klanary.jigeumchulbal.fakes.testSessionStore
import io.github.klanary.jigeumchulbal.session.SessionController
import io.github.klanary.jigeumchulbal.session.SessionEvent
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.session.TrainSource
import io.github.klanary.jigeumchulbal.session.line5to6Route
import io.github.klanary.jigeumchulbal.session.nearTrain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 탑승 중 탄 열차 직접 고르기(⑭ 3번, 위치 결합 스펙 7.1). */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTrainPickerTest {
    @get:Rule val main = MainDispatcherRule()

    private val trains = FakeTrainRepository()
    private lateinit var controller: SessionController

    private suspend fun viewModel(): SessionViewModel {
        controller = SessionController(testSessionStore(), FakeAlarmScheduler(), FakeNotifier(), FakeLauncher()) { 0L }
        controller.start(SessionEvent.StartRiding(line5to6Route(), AppSettings()), inAppOnly = false)
        return SessionViewModel(controller, { 0L }, FakeStationRepository(), trains = trains)
    }

    @Test fun pickerAsksNearTrainsAtBoardingStationTowardNextStop() = runTest {
        trains.nearResult = ApiResult.Ok(LineTrainsDto("5호선", "t", trains = listOf(nearTrain("하남검단산", "충정로"))))
        val vm = viewModel()
        vm.openTrainPicker()
        advanceUntilIdle()
        assertEquals(listOf(Triple("5호선", "광화문", "서대문")), trains.nearCalls)
        assertEquals(PickerState.Loaded(listOf(nearTrain("하남검단산", "충정로"))), vm.picker.value)
    }

    @Test fun choosingIdentifiesTrainManually() = runTest {
        val vm = viewModel()
        vm.chooseTrain(nearTrain("하남검단산", "충정로"))
        advanceUntilIdle()
        val t = (controller.state.value as SessionState.Riding).tracking!!
        assertEquals(Triple("5123", TrainSource.MANUAL, "하남검단산"), Triple(t.trainNo, t.source, t.destination))
        assertEquals(PickerState.Hidden, vm.picker.value)
    }

    @Test fun nearErrorShowsServerMessage() = runTest {
        trains.nearResult = ApiResult.Err(ApiError.Server("NOT_FOUND", "LINE_NOT_SUPPORTED", "이 호선은 열차 위치 정보를 지원하지 않아요."))
        val vm = viewModel()
        vm.openTrainPicker()
        advanceUntilIdle()
        assertEquals(PickerState.Failed("이 호선은 열차 위치 정보를 지원하지 않아요."), vm.picker.value)
    }
}
