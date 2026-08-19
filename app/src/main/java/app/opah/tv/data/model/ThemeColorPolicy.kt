package app.opah.tv.data.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class HslColor(
    val hue: Int,
    val saturation: Int,
    val lightness: Int,
)

object ThemeColorPolicy {
    const val MIN_ACCENT_CONTRAST = 3.0

    fun sanitize(colors: CustomThemeColors): CustomThemeColors {
        val background = opaque(colors.backgroundArgb)
        return colors.copy(
            accentArgb = ensureContrast(opaque(colors.accentArgb), background),
            backgroundArgb = background,
        )
    }

    fun readableForeground(backgroundArgb: Int): Int {
        val background = opaque(backgroundArgb)
        val light = 0xFFF8FAFF.toInt()
        val dark = 0xFF101722.toInt()
        return if (contrastRatio(light, background) >= contrastRatio(dark, background)) light else dark
    }

    fun secondaryAccent(colors: CustomThemeColors): Int {
        val safe = sanitize(colors)
        val hsl = toHsl(safe.accentArgb)
        return ensureContrast(
            hslToArgb(hsl.copy(hue = (hsl.hue + 38) % 360)),
            safe.backgroundArgb,
        )
    }

    fun adjustHue(argb: Int, amount: Int): Int {
        val hsl = toHsl(argb)
        val hue = ((hsl.hue + amount) % 360 + 360) % 360
        return hslToArgb(hsl.copy(hue = hue))
    }

    fun adjustSaturation(argb: Int, amount: Int): Int {
        val hsl = toHsl(argb)
        return hslToArgb(hsl.copy(saturation = (hsl.saturation + amount).coerceIn(0, 100)))
    }

    fun adjustLightness(argb: Int, amount: Int): Int {
        val hsl = toHsl(argb)
        return hslToArgb(hsl.copy(lightness = (hsl.lightness + amount).coerceIn(5, 95)))
    }

    fun toHsl(argb: Int): HslColor {
        val red = ((argb shr 16) and 0xFF) / 255.0
        val green = ((argb shr 8) and 0xFF) / 255.0
        val blue = (argb and 0xFF) / 255.0
        val maximum = max(red, max(green, blue))
        val minimum = min(red, min(green, blue))
        val delta = maximum - minimum
        val lightness = (maximum + minimum) / 2.0
        val saturation = if (delta == 0.0) {
            0.0
        } else {
            delta / (1.0 - abs(2.0 * lightness - 1.0))
        }
        val hue = when {
            delta == 0.0 -> 0.0
            maximum == red -> 60.0 * (((green - blue) / delta) % 6.0)
            maximum == green -> 60.0 * (((blue - red) / delta) + 2.0)
            else -> 60.0 * (((red - green) / delta) + 4.0)
        }
        return HslColor(
            hue = ((hue.roundToInt() % 360) + 360) % 360,
            saturation = (saturation * 100.0).roundToInt().coerceIn(0, 100),
            lightness = (lightness * 100.0).roundToInt().coerceIn(0, 100),
        )
    }

    fun hslToArgb(hsl: HslColor): Int {
        val hue = ((hsl.hue % 360) + 360) % 360 / 360.0
        val saturation = hsl.saturation.coerceIn(0, 100) / 100.0
        val lightness = hsl.lightness.coerceIn(0, 100) / 100.0
        if (saturation == 0.0) {
            val channel = (lightness * 255.0).roundToInt().coerceIn(0, 255)
            return opaque((channel shl 16) or (channel shl 8) or channel)
        }
        val q = if (lightness < 0.5) {
            lightness * (1.0 + saturation)
        } else {
            lightness + saturation - lightness * saturation
        }
        val p = 2.0 * lightness - q
        val red = hueToRgb(p, q, hue + 1.0 / 3.0)
        val green = hueToRgb(p, q, hue)
        val blue = hueToRgb(p, q, hue - 1.0 / 3.0)
        return opaque(
            ((red * 255.0).roundToInt().coerceIn(0, 255) shl 16) or
                ((green * 255.0).roundToInt().coerceIn(0, 255) shl 8) or
                (blue * 255.0).roundToInt().coerceIn(0, 255),
        )
    }

    fun contrastRatio(firstArgb: Int, secondArgb: Int): Double {
        val first = relativeLuminance(firstArgb)
        val second = relativeLuminance(secondArgb)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    private fun ensureContrast(accentArgb: Int, backgroundArgb: Int): Int {
        if (contrastRatio(accentArgb, backgroundArgb) >= MIN_ACCENT_CONTRAST) return accentArgb
        val source = toHsl(accentArgb)
        val light = 0xFFFFFFFF.toInt()
        val dark = 0xFF000000.toInt()
        val increase = contrastRatio(light, backgroundArgb) >= contrastRatio(dark, backgroundArgb)
        for (step in 1..100) {
            val lightness = if (increase) source.lightness + step else source.lightness - step
            if (lightness !in 0..100) break
            val candidate = hslToArgb(source.copy(lightness = lightness))
            if (contrastRatio(candidate, backgroundArgb) >= MIN_ACCENT_CONTRAST) return candidate
        }
        return if (increase) light else dark
    }

    private fun relativeLuminance(argb: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92
            else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        val red = channel((argb shr 16) and 0xFF)
        val green = channel((argb shr 8) and 0xFF)
        val blue = channel(argb and 0xFF)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    private fun hueToRgb(p: Double, q: Double, raw: Double): Double {
        val value = when {
            raw < 0.0 -> raw + 1.0
            raw > 1.0 -> raw - 1.0
            else -> raw
        }
        return when {
            value < 1.0 / 6.0 -> p + (q - p) * 6.0 * value
            value < 1.0 / 2.0 -> q
            value < 2.0 / 3.0 -> p + (q - p) * (2.0 / 3.0 - value) * 6.0
            else -> p
        }
    }

    private fun opaque(argb: Int): Int = argb or 0xFF000000.toInt()
}
