package io.github.klanary.jigeumchulbal.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/** 새 색 구성의 글자 대비가 WCAG AA(4.5:1) 이상인지(2차 다듬기 Review Focus 1). */
class PaletteContrastTest {
    private fun assertAA(fg: Color, bg: Color, name: String) {
        val r = contrastRatio(fg, bg)
        assertTrue("$name ${"%.2f".format(r)}", r >= 4.5)
    }

    private fun checkAll(c: ColorScheme, theme: String) {
        assertAA(c.onSurface, c.surface, "$theme onSurface")
        assertAA(c.onSurfaceVariant, c.surface, "$theme onSurfaceVariant")
        assertAA(c.onSurfaceVariant, c.background, "$theme onSurfaceVariant/bg")
        assertAA(c.onPrimary, c.primary, "$theme onPrimary")
        assertAA(c.onPrimaryContainer, c.primaryContainer, "$theme onPrimaryContainer")
        assertAA(c.onTertiaryContainer, c.tertiaryContainer, "$theme onTertiaryContainer")
        assertAA(c.primary, c.surface, "$theme primary text on surface")
    }

    @Test fun lightTextIsReadable() = checkAll(LightColors, "light")

    @Test fun darkTextIsReadable() = checkAll(DarkColors, "dark")

    /**
     * 강조색이 1호선 남색과 눈으로 구분되어야 한다(스펙 4.3). 둘 다 어두운 파랑이라 밝기 대비로는 잴 수 없어
     * 색 차이(CIE76 ΔE)로 잰다. 15 이상이면 나란히 놓았을 때 분명히 다르게 보인다.
     */
    @Test fun accentIsNotLine1Blue() {
        val d = deltaE(LightColors.primary, Color(0xFF0052A4))
        assertTrue("ΔE ${"%.1f".format(d)}", d >= 15.0)
    }

    private fun deltaE(a: Color, b: Color): Double {
        val (l1, a1, b1) = lab(a)
        val (l2, a2, b2) = lab(b)
        return Math.sqrt((l1 - l2) * (l1 - l2) + (a1 - a2) * (a1 - a2) + (b1 - b2) * (b1 - b2))
    }

    /** sRGB → CIE Lab(D65). */
    private fun lab(c: Color): Triple<Double, Double, Double> {
        fun lin(v: Float): Double = if (v <= 0.04045f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        val r = lin(c.red); val g = lin(c.green); val bl = lin(c.blue)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * bl) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * bl
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * bl) / 1.08883
        fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116
        return Triple(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }
}
