package app.cloudsaver

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cloudsaver.core.logic.ThemeMode
import app.cloudsaver.ui.components.MetricGrid
import app.cloudsaver.ui.components.MetricTile
import app.cloudsaver.ui.components.TileHeight
import app.cloudsaver.ui.theme.CloudSaverTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dashboard tile has to hold its cell at every count it can show.
 *
 * The bug this guards: a tile that grows or collapses with the length of its
 * number makes the whole grid jump as counts tick over, which reads as a
 * rendering fault rather than as progress.
 *
 * Assertions go through the tile's content description, because [MetricTile]
 * deliberately collapses its two Texts into one label for screen readers -
 * "99,999" and "Checked" announced as separate nodes is worse than
 * "Checked: 99,999". That is also the only text a semantics query can see.
 */
@RunWith(AndroidJUnit4::class)
class MetricTileTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showTile(value: String, width: Int) {
        compose.setContent {
            CloudSaverTheme(mode = ThemeMode.LIGHT, dynamicColor = false) {
                MetricTile(
                    value = value,
                    label = "Waiting",
                    modifier = Modifier.width(width.dp)
                )
            }
        }
    }

    private fun assertHoldsItsCell(value: String, width: Int) {
        compose.onNodeWithContentDescription("Waiting: $value")
            .assertIsDisplayed()
            .assertWidthIsEqualTo(width.dp)
            .assertHeightIsEqualTo(TileHeight)
    }

    @Test
    fun oneDigitHoldsTheCell() {
        showTile("1", 120)
        assertHoldsItsCell("1", 120)
    }

    @Test
    fun twoDigitsHoldTheCell() {
        showTile("12", 120)
        assertHoldsItsCell("12", 120)
    }

    @Test
    fun fourDigitsHoldTheCell() {
        showTile("2,411", 120)
        assertHoldsItsCell("2,411", 120)
    }

    @Test
    fun fiveDigitsHoldTheCellEvenWhenNarrow() {
        // The width a four-up row leaves on a small phone.
        showTile("99,999", 96)
        assertHoldsItsCell("99,999", 96)
    }

    @Test
    fun everyTileInAGridIsDisplayed() {
        val tiles = listOf(
            "1" to "Waiting",
            "12" to "Copied",
            "2,411" to "Uploaded",
            "99,999" to "Checked"
        )
        compose.setContent {
            CloudSaverTheme(mode = ThemeMode.LIGHT, dynamicColor = false) {
                MetricGrid(
                    tiles = tiles.map { (value, label) ->
                        { m: Modifier -> MetricTile(value, label, m) }
                    }
                )
            }
        }
        // Four tiles means two rows of two, and every cell must be on screen.
        for ((value, label) in tiles) {
            compose.onNodeWithContentDescription("$label: $value")
                .assertIsDisplayed()
                .assertHeightIsEqualTo(TileHeight)
        }
    }
}
