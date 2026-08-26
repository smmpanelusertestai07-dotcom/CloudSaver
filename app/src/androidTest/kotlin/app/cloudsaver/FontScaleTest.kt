package app.cloudsaver

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cloudsaver.core.logic.ThemeMode
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.MetricGrid
import app.cloudsaver.ui.components.MetricTile
import app.cloudsaver.ui.components.SectionHeader
import app.cloudsaver.ui.theme.CloudSaverTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app has to survive someone who needs large text.
 *
 * At 200% every fixed-height box, every single-line label and every row that
 * assumed its text would fit becomes a clipping bug, and the person most
 * affected is the one least able to work around it. These are the components
 * with a fixed height or a squeeze, checked at the scale that breaks them.
 */
@RunWith(AndroidJUnit4::class)
class FontScaleTest {

    /** Any failure below leaves a picture of the screen behind it. */
    @get:Rule
    val shotOnFailure = ScreenshotOnFailure()

    @get:Rule
    val compose = createComposeRule()

    private fun atDoubleText(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale = 2f)
            ) {
                CloudSaverTheme(mode = ThemeMode.LIGHT, dynamicColor = false) { content() }
            }
        }
    }

    @Test
    fun aMetricTileSurvivesDoubleTextSize() {
        atDoubleText {
            MetricTile("2,411", "Waiting to optimise", Modifier.width(150.dp))
        }
        compose.onNodeWithContentDescription("Waiting to optimise: 2,411").assertIsDisplayed()
    }

    @Test
    fun aFourUpGridSurvivesDoubleTextSize() {
        val tiles = listOf(
            "1" to "Waiting to optimise",
            "12" to "In your upload folder",
            "2,411" to "Backed up",
            "99,999" to "Skipped"
        )
        atDoubleText {
            androidx.compose.foundation.layout.Column {
                MetricGrid(
                    tiles.map { (value, label) ->
                        { m: Modifier -> MetricTile(value, label, m) }
                    }
                )
            }
        }
        for ((value, label) in tiles) {
            compose.onNodeWithContentDescription("$label: $value").assertIsDisplayed()
        }
    }

    @Test
    fun aSectionHeaderAndCardSurviveDoubleTextSize() {
        atDoubleText {
            androidx.compose.foundation.layout.Column {
                SectionHeader("CloudSaver's own space")
                AppCard {
                    androidx.compose.material3.Text("Waiting in your upload folder")
                }
            }
        }
        compose.onNodeWithText("CloudSaver's own space").assertIsDisplayed()
        compose.onNodeWithText("Waiting in your upload folder").assertIsDisplayed()
    }
}
