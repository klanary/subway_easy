package io.github.klanary.jigeumchulbal.ui.common

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** 밝은 호선 색(9호선·수인분당선 등)에는 검정 글자, 어두운 색에는 흰 글자(최종 리뷰 Important 2). */
class LineBadgeColorTest {
    @Test fun lightLinesGetDarkText() {
        listOf("9호선", "수인분당선", "우이신설선", "서해선", "경의중앙선").forEach {
            assertEquals(it, Color.Black, badgeTextColor(lineColor(it)))
        }
    }

    @Test fun darkLinesGetWhiteText() {
        listOf("1호선", "2호선", "5호선", "7호선").forEach {
            assertEquals(it, Color.White, badgeTextColor(lineColor(it)))
        }
    }
}
