package app.cloudsaver

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cloudsaver.ui.components.MetricGrid
import app.cloudsaver.ui.components.MetricTile
import app.cloudsaver.ui.theme.CloudSaverTheme
import app.cloudsaver.core.logic.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dashboard counter has to stay readable at every width it can reach.
 *
 * Two bugs live here. One: the counter used to cross-fade two overlapping
 * Texts, so a changing number rendered as a ghost of the old one on top of the
 * new. Two: a five-digit count in a narrow tile clipped instead of shrinking.
 * Both look like a rendering fault to the person holding the phone.
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

    @Test
    fun oneDigitIsDisplayed() {
        showTile("1", 120)
        compose.onNodeWithText("1").assertIsDisplayed()
    }

    @Test
    fun twoDigitsAreDisplayed() {
        showTile("12", 120)
        compose.onNodeWithText("12").assertIsDisplayed()
    }

    @Test
    fun fourDigitsAreDisplayed() {
        showTile("2,411", 120)
        compose.onNodeWithText("2,411").assertIsDisplayed()
    }

    @Test
    fun fiveDigitsAreDisplayedEvenInANarrowTile() {
        // The width that used to clip.
        showTile("99,999", 96)
        compose.onNodeWithText("99,999").assertIsDisplayed()
    }

    @Test
    fun everyTileInAGridIsDisplayed() {
        compose.setContent {
            CloudSaverTheme(mode = ThemeMode.LIGHT, dynamicColor = false) {
                MetricGrid(
                    tiles = listOf<@androidx.compose.runtime.Composable (Modifier) -> Unit>(
                        { m -> MetricTile("1", "Waiting", m) },
                        { m -> MetricTile("12", "Copied", m) },
                        { m -> MetricTile("2,411", "Uploaded", m) },
                        { m -> MetricTile("99,999", "Checked", m) }
                    )
                )
            }
        }
        for (value in listOf("1", "12", "2,411", "99,999")) {
            compose.onNodeWithText(value).assertIsDisplayed()
        }
    }
}
