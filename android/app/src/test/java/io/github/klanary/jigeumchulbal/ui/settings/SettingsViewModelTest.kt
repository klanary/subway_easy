package io.github.klanary.jigeumchulbal.ui.settings

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.local.AppSettings
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
        val vm = SettingsViewModel(fakeSettingsStore())
        advanceUntilIdle()
        assertEquals(AppSettings(), vm.state.value.settings)
    }


    @Test(expected = IllegalArgumentException::class)
    fun valueOutsideOptionsIsRejected() = SettingsViewModel(fakeSettingsStore()).setDepartMargin(45)

    @Test fun permissionRowsHideNotApplicable() =
        assertEquals(listOf(PermissionRow("위치", true)), permissionRows(location = true, notifications = null, exactAlarm = null))
}
