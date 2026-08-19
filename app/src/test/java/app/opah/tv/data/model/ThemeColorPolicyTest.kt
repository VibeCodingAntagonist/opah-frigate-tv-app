package app.opah.tv.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorPolicyTest {
    @Test
    fun `unsafe accent is adjusted to the protected contrast minimum`() {
        val colors = ThemeColorPolicy.sanitize(
            CustomThemeColors(
                accentArgb = 0xFF777777.toInt(),
                backgroundArgb = 0xFF777777.toInt(),
            ),
        )

        assertEquals(0xFF777777.toInt(), colors.backgroundArgb)
        assertTrue(
            ThemeColorPolicy.contrastRatio(colors.accentArgb, colors.backgroundArgb) >=
                ThemeColorPolicy.MIN_ACCENT_CONTRAST,
        )
    }

    @Test
    fun `contrast protection can darken an accent on a light midtone background`() {
        val colors = ThemeColorPolicy.sanitize(
            CustomThemeColors(
                accentArgb = 0xFFB0B0B0.toInt(),
                backgroundArgb = 0xFFB0B0B0.toInt(),
            ),
        )

        assertTrue(ThemeColorPolicy.toHsl(colors.accentArgb).lightness < 69)
        assertTrue(
            ThemeColorPolicy.contrastRatio(colors.accentArgb, colors.backgroundArgb) >=
                ThemeColorPolicy.MIN_ACCENT_CONTRAST,
        )
    }

    @Test
    fun `foreground policy selects a readable light or dark color`() {
        assertEquals(0xFFF8FAFF.toInt(), ThemeColorPolicy.readableForeground(0xFF07111F.toInt()))
        assertEquals(0xFF101722.toInt(), ThemeColorPolicy.readableForeground(0xFFF7F9FD.toInt()))
    }

    @Test
    fun `secondary accent remains distinct and protected`() {
        val colors = ThemeColorPolicy.sanitize(CustomThemeColors())
        val secondary = ThemeColorPolicy.secondaryAccent(colors)

        assertTrue(secondary != colors.accentArgb)
        assertTrue(
            ThemeColorPolicy.contrastRatio(secondary, colors.backgroundArgb) >=
                ThemeColorPolicy.MIN_ACCENT_CONTRAST,
        )
    }

    @Test
    fun `d pad adjustments wrap hue and clamp color channels`() {
        val red = ThemeColorPolicy.hslToArgb(HslColor(hue = 355, saturation = 100, lightness = 50))
        assertEquals(10, ThemeColorPolicy.toHsl(ThemeColorPolicy.adjustHue(red, 15)).hue)

        val muted = ThemeColorPolicy.hslToArgb(HslColor(hue = 120, saturation = 2, lightness = 7))
        val adjusted = ThemeColorPolicy.toHsl(ThemeColorPolicy.adjustSaturation(muted, -5))
        assertEquals(0, adjusted.saturation)
        assertTrue(ThemeColorPolicy.toHsl(ThemeColorPolicy.adjustLightness(muted, -10)).lightness >= 5)
    }

    @Test
    fun `hsl conversion preserves representative colors within rounding tolerance`() {
        listOf(
            0xFFFF7048.toInt(),
            0xFF07111F.toInt(),
            0xFF35A7FF.toInt(),
            0xFFF7F9FD.toInt(),
        ).forEach { original ->
            val converted = ThemeColorPolicy.hslToArgb(ThemeColorPolicy.toHsl(original))
            for (shift in listOf(16, 8, 0)) {
                val delta = kotlin.math.abs(((original shr shift) and 0xFF) - ((converted shr shift) and 0xFF))
                assertTrue("channel delta was $delta", delta <= 4)
            }
        }
    }
}
