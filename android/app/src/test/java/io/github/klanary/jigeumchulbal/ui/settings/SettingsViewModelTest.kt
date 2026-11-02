package io.github.klanary.jigeumchulbal.ui.settings

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.data.local.MyPlaceType
import io.github.klanary.jigeumchulbal.data.model.Endpoint
import io.github.klanary.jigeumchulbal.data.model.EndpointKind
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.fakes.FakeMyPlaceRepository
import io.github.klanary.jigeumchulbal.fakes.fakeSettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun defaultsComeFromStore() = runTest {
        val vm = SettingsViewModel(FakeMyPlaceRepository(), fakeSettingsStore())
        advanceUntilIdle()
        assertEquals(AppSettings(), vm.state.value.settings)
    }

    @Test fun savingHomeUsesHomeType() = runTest {
        val places = FakeMyPlaceRepository()
        SettingsViewModel(places, fakeSettingsStore()).savePlace(
            MyPlaceType.HOME,
            "집",
            Endpoint("서울 마포구 양화로 160", GeoPoint(37.5571, 126.9245), EndpointKind.PIN),
        )
        advanceUntilIdle()
        assertEquals(Triple(MyPlaceType.HOME, "집", "서울 마포구 양화로 160"), places.saved.single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun valueOutsideOptionsIsRejected() = SettingsViewModel(FakeMyPlaceRepository(), fakeSettingsStore()).setDepartMargin(45)

    @Test fun permissionRowsHideNotApplicable() =
        assertEquals(listOf(PermissionRow("위치", true)), permissionRows(location = true, notifications = null, exactAlarm = null))
}
