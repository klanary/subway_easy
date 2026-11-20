package io.github.klanary.jigeumchulbal.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 허용된 권한은 ✓만, 허용 안 된 권한만 [설정 열기](Task 10 합의). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsContentTest {
    @get:Rule val rule = createComposeRule()

    @Test fun onlyMissingPermissionHasButton() {
        val opened = mutableListOf<String>()
        rule.setContent {
            AppTheme(darkTheme = false) {
                SettingsContent(
                    SettingsUiState(AppSettings()),
                    listOf(PermissionRow(Copy.PERMISSION_LOCATION, true), PermissionRow(Copy.PERMISSION_NOTIFICATIONS, false)),
                    onOpenPermission = { opened += it.title },
                )
            }
        }
        // 권한 줄은 목록 아래쪽이라 먼저 내려 간다.
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(Copy.PERMISSION_NOTIFICATIONS))
        rule.onAllNodesWithText(Copy.OPEN_SETTINGS).assertCountEquals(1)
        rule.onAllNodesWithText(Copy.OPEN_SETTINGS)[0].performClick()
        assertEquals(listOf(Copy.PERMISSION_NOTIFICATIONS), opened)
    }
}
