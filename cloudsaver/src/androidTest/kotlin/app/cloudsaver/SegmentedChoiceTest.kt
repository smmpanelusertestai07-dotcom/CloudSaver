package app.cloudsaver

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cloudsaver.core.logic.ThemeMode
import app.cloudsaver.ui.components.SegmentedChoice
import app.cloudsaver.ui.theme.CloudSaverTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Options in a segmented control have to stay readable in the width they get.
 *
 * This has now gone wrong three times, each time only visible in a screenshot:
 * "Unlimited" clipped to "Unlimi"; "Photos and videos" clipped to "Photos and
 * vide" once shrinking alone proved not to be enough; and then, once labels
 * were allowed a second line, "Unlimited" broken across two lines as "Unlimit"
 * over "ed" - because with two lines to fill, the shrinking stops as soon as
 * the text fits across both, and a single word has no space to wrap at.
 *
 * The rule the control has to keep: a label made of one word occupies one
 * line, however small it has to be to manage it. A label that can wrap may
 * take two. Both are checked here by height, against a label in the same row
 * that is known to fit on one line, so the assertion does not depend on the
 * font scale or the density of whatever this is running on.
 */
@RunWith(AndroidJUnit4::class)
class SegmentedChoiceTest {

    /** Any failure below leaves a picture of the screen behind it. */
    @get:Rule
    val shotOnFailure = ScreenshotOnFailure()

    @get:Rule
    val compose = createComposeRule()

    private fun showChoice(labels: List<String>, width: Int) {
        compose.setContent {
            CloudSaverTheme(mode = ThemeMode.LIGHT, dynamicColor = false) {
                Box(Modifier.width(width.dp)) {
                    SegmentedChoice(
                        options = labels.map { it to it },
                        selected = labels.first(),
                        onSelect = {}
                    )
                }
            }
        }
    }

    private fun heightOf(label: String) =
        compose.onNodeWithText(label).getUnclippedBoundsInRoot()
            .let { it.bottom - it.top }

    private fun assertOnOneLine(label: String, reference: String) {
        compose.onNodeWithText(label).assertIsDisplayed()
        val tall = heightOf(label)
        val oneLine = heightOf(reference)
        assertTrue(
            "\"$label\" is $tall tall where the one-line \"$reference\" is " +
                "$oneLine - it has been broken across lines",
            tall < oneLine * 1.5f
        )
    }

    /** The four space budgets, which is where "Unlimited" kept breaking. */
    @Test
    fun aOneWordOptionIsNeverBrokenInHalf() {
        showChoice(listOf("All", "3 GB", "5 GB", "Unlimited"), width = 360)
        assertOnOneLine("Unlimited", reference = "All")
    }

    @Test
    fun aOneWordOptionSurvivesANarrowPhone() {
        showChoice(listOf("All", "3 GB", "5 GB", "Unlimited"), width = 320)
        assertOnOneLine("Unlimited", reference = "All")
    }

    /** The Activity filters, where "Problems" came out as "Problem" over "s". */
    @Test
    fun theActivityFiltersStayWhole() {
        showChoice(listOf("All", "Backups", "Problems", "Changes"), width = 320)
        assertOnOneLine("Problems", reference = "All")
        assertOnOneLine("Changes", reference = "All")
        assertOnOneLine("Backups", reference = "All")
    }

    /**
     * A label with spaces in it may take a second line, and no more.
     *
     * Deliberately an upper bound rather than "it wrapped": whether shrinking
     * alone gets "Photos and videos" onto one line depends on the width it is
     * given, and both outcomes are correct. What is not correct is a third
     * line, which is what an unbounded label would take.
     */
    @Test
    fun aLabelWithSpacesInItTakesAtMostTwoLines() {
        showChoice(listOf("Photos", "Videos", "Photos and videos"), width = 320)
        compose.onNodeWithText("Photos and videos").assertIsDisplayed()
        val wrapped = heightOf("Photos and videos")
        val oneLine = heightOf("Photos")
        assertTrue(
            "\"Photos and videos\" is $wrapped tall against a one-line " +
                "$oneLine - more than the two lines it is allowed",
            wrapped < oneLine * 2.5f
        )
    }
}
