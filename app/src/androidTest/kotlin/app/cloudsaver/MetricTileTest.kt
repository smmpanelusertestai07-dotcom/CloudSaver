package app.cloudsaver

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cloudsaver.core.logic.ThemeMode
import app.cloudsaver.ui.components.MetricGrid
import app.cloudsaver.ui.components.MetricTile
import app.cloudsaver.ui.theme.CloudSaverTheme
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dashboard tile has to hold its cell at every count it can show.
 *
 * The bug this guards: a tile that grows or shrinks with the length of its
 * number makes the whole grid jump as counts tick over, which reads as a
 * rendering fault rather than as progress.
 *
 * Two things about how these assertions are written, both learned the hard
 * way. Queries go through the content description, because [MetricTile] ends
 * its modifier chain with clearAndSetSemantics - "Checked: 99,999" is one
 * announcement rather than two unrelated nodes for a screen reader, and it is
 * the only text a semantics query can see. And sizes are compared between
 * tiles rather than against numbers written here: the semantics node sits
 * inside the tile's padding, so any literal would be encoding a padding
 * constant and would break the day that padding changes, without anything
 * actually being wrong.
 */
@RunWith(AndroidJUnit4::class)
class MetricTileTest {

    /** Any failure below leaves a picture of the screen behind it. */
    @get:Rule
    val shotOnFailure = ScreenshotOnFailure()

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

    @Test
    fun oneDigitIsDisplayed() {
        showTile("1", 120)
        compose.onNodeWithContentDescription("Waiting: 1").assertIsDisplayed()
    }

    @Test
    fun twoDigitsAreDisplayed() {
        showTile("12", 120)
        compose.onNodeWithContentDescription("Waiting: 12").assertIsDisplayed()
    }

    @Test
    fun fourDigitsAreDisplayed() {
        showTile("2,411", 120)
        compose.onNodeWithContentDescription("Waiting: 2,411").assertIsDisplayed()
    }

    @Test
    fun fiveDigitsAreDisplayedInANarrowTile() {
        // The width a four-up row leaves on a small phone.
        showTile("99,999", 96)
        compose.onNodeWithContentDescription("Waiting: 99,999").assertIsDisplayed()
    }

    @Test
    fun aLongNumberDoesNotChangeTheCell() {
        // Two equal cells side by side, exactly as the grid lays them out.
        // Whatever each holds, the two must come out the same size.
        compose.setContent {
            CloudSaverTheme(mode = ThemeMode.LIGHT, dynamicColor = false) {
                // Narrower than the test device, so neither cell can be
                // clipped by the window edge and hide a real difference.
                Row(Modifier.width(280.dp)) {
                    MetricTile("1", "Waiting", Modifier.weight(1f))
                    MetricTile("99,999", "Checked", Modifier.weight(1f))
                }
            }
        }
        val short = compose.onNodeWithContentDescription("Waiting: 1")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val long = compose.onNodeWithContentDescription("Checked: 99,999")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertSameToThePixel(short.right - short.left, long.right - long.left)
        assertSameToThePixel(short.bottom - short.top, long.bottom - long.top)
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
                // A Column, because MetricGrid emits one Row per grid row and
                // every screen that uses it stacks them.
                Column {
                    MetricGrid(
                        tiles = tiles.map { (value, label) ->
                            { m: Modifier -> MetricTile(value, label, m) }
                        }
                    )
                }
            }
        }
        for ((value, label) in tiles) {
            compose.onNodeWithContentDescription("$label: $value").assertIsDisplayed()
        }
    }

    /**
     * Equal, allowing the one pixel that weighted layout cannot split.
     *
     * Two `weight(1f)` cells share an odd number of pixels by giving one of
     * them the remainder: at 420 dpi a 280 dp row is 735 px, so the cells come
     * out 368 px and 367 px - a real, unavoidable 0.38 dp difference that says
     * nothing about the tile. Anything wider than a pixel is the bug this test
     * is looking for.
     */
    private fun assertSameToThePixel(a: Dp, b: Dp) {
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        val apart = abs(a.value - b.value) * density
        assertTrue(
            "the two cells are $a and $b, ${"%.2f".format(apart)} px apart",
            apart <= 1.01f
        )
    }
}
