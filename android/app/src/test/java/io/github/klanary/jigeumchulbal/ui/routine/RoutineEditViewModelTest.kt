package io.github.klanary.jigeumchulbal.ui.routine

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.RoutesDto
import io.github.klanary.jigeumchulbal.data.remote.TrainDto
import io.github.klanary.jigeumchulbal.fakes.FakeRouteRepository
import io.github.klanary.jigeumchulbal.fixtures.specRoute
import io.github.klanary.jigeumchulbal.routine.FakeRoutineRepository
import io.github.klanary.jigeumchulbal.routine.RoutineDraft
import io.github.klanary.jigeumchulbal.routine.dayBit
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineEditViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val json = Json { ignoreUnknownKeys = true }
    private val kst = ZoneId.of("Asia/Seoul")
    private val now = ZonedDateTime.of(2026, 11, 13, 7, 0, 0, 0, kst) // 금요일 07:00
    private val route = specRoute() // 첫 탑승까지 걷는 시간 420초
    private val draft = RoutineDraft(StationRef("홍대입구", "홍대입구"), null, StationRef("시청", "시청"), json.encodeToString(route))
    private val repo = FakeRoutineRepository()
    private val routes = FakeRouteRepository().apply {
        val fb = route.firstBoarding!!
        val scheduled = route.copy(firstBoarding = fb.copy(nextTrains = listOf(TrainDto("성수", 0, "08:14 출발"), TrainDto("성수", 0, "08:22 출발"))))
        result = ApiResult.Ok(RoutesDto("2026-11-13T07:00:00+09:00", routes = listOf(scheduled)))
    }

    private fun vm(id: Long? = null) = RoutineEditViewModel(id, draft, repo, routes, json) { now }

    @Test fun newRoutineDefaultsToWeekdaysAt8() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(480, s.minuteOfDay)
        assertEquals(5, s.walkMin)
        assertEquals(DayOfWeek.entries.take(5).sumOf { dayBit(it) }, s.days)
        assertFalse(s.alightAlerts)
    }

    @Test fun previewShowsTrainsAfterWalk() = runTest {
        val vm = vm()
        vm.setTime(490)
        advanceUntilIdle()
        // 오늘(금) 08:10 + 걷기 5분 = 08:15에 역에 닿게: 서버는 departAt + 탑승 전 시간(420초) 이후 열차를 준다.
        val expected = now.withHour(8).withMinute(15).minusSeconds(420).toInstant()
        assertEquals(expected, routes.calls.last().departAt)
        assertEquals(listOf("08:14", "08:22"), vm.state.value.preview)
    }

    @Test fun cannotEnableWithoutNotificationPermission() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.save(hasNotificationPermission = false)
        advanceUntilIdle()
        assertFalse(repo.routines.value.single().enabled)
        assertEquals(io.github.klanary.jigeumchulbal.ui.common.Copy.NEED_NOTIFICATION_PERMISSION, vm.state.value.message)
    }

    @Test fun savingWithPermissionEnables() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.toggleDay(DayOfWeek.SATURDAY)
        vm.setWalk(40)
        vm.setAlightAlerts(true)
        vm.save(hasNotificationPermission = true)
        advanceUntilIdle()
        val saved = repo.routines.value.single()
        assertTrue(saved.enabled && saved.alightAlerts)
        assertEquals(30, saved.walkMin) // 0~30분으로 제한
        assertTrue(saved.days and dayBit(DayOfWeek.SATURDAY) != 0)
        assertTrue(vm.state.value.saved)
    }

    @Test fun editingLoadsExistingRoutine() = runTest {
        val id = vm().let { it.save(true); advanceUntilIdle(); repo.routines.value.single().id }
        repo.save(repo.routines.value.single().copy(minuteOfDay = 600))
        val vm = vm(id)
        advanceUntilIdle()
        assertEquals(600, vm.state.value.minuteOfDay)
    }
}
