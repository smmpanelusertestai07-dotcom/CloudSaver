package com.pocketide.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.pocketide.ui.components.STATUS_CHIP_TINT
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/** State colours carry meaning in small text ("Needs you", backup state), so they must be readable. */
class StatusContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun cards(scheme: ColorScheme) = mapOf(
        "background" to scheme.background,
        "surfaceContainerLowest" to scheme.surfaceContainerLowest,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
    )

    private fun tones(status: StatusColors) = mapOf("ok" to status.ok, "warn" to status.warn, "error" to status.error, "neutral" to status.neutral)

    private fun check(mode: String, scheme: ColorScheme, status: StatusColors) {
        val failures = mutableListOf<String>()
        for ((toneName, tone) in tones(status)) {
            for ((cardName, card) in cards(scheme)) {
                val asText = contrast(tone, card)
                val chip = tone.copy(alpha = STATUS_CHIP_TINT).compositeOver(card)
                val onChip = contrast(tone, chip)
                if (asText < AA_SMALL_TEXT) failures += "$mode $toneName on $cardName: %.2f".format(asText)
                if (onChip < AA_SMALL_TEXT) failures += "$mode $toneName chip on $cardName: %.2f".format(onChip)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun lightStatusTextAndChipsMeetAa() = check("light", Light, LightStatus)

    @Test
    fun darkStatusTextAndChipsMeetAa() = check("dark", Dark, DarkStatus)

    private companion object {
        const val AA_SMALL_TEXT = 4.5
    }
}
