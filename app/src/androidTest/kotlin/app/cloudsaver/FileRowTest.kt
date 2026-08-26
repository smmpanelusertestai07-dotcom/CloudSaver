package app.cloudsaver

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cloudsaver.core.logic.ThemeMode
import app.cloudsaver.ui.components.FileRow
import app.cloudsaver.ui.theme.CloudSaverTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A file row has to say which file it is, whatever else it is also saying.
 *
 * The bug this guards was found by looking at a screenshot, not by any of the
 * eighty-odd tests that ran over the same screen: on Largest files every row
 * showed a thumbnail, a size and "about 459 KB after optimising", and no file
 * name at all. The trailing column carries no weight, so it measured at the
 * full width of that sentence and the name's weight(1f) was left with nothing.
 * Every row on a screen whose entire job is telling you which file is which
 * was anonymous.
 *
 * Rendering it in a fixed-width box is the point: the failure only appears
 * when the row is narrow enough for the two columns to compete, which is every
 * phone, and never in a test that lets the row be as wide as it likes.
 */
@RunWith(AndroidJUnit4::class)
class FileRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val name = "IMG_20240517_181233.jpg"

    private fun showRow(trailingNote: String?, width: Int = 360) {
        compose.setContent {
            CloudSaverTheme(mode = ThemeMode.LIGHT, dynamicColor = false) {
                Box(Modifier.width(width.dp)) {
                    FileRow(
                        name = name,
                        context = "643 KB - waiting",
                        size = "643 KB",
                        proof = null,
                        thumbnail = { Box(Modifier.size(56.dp)) },
                        actions = emptyList(),
                        trailingNote = trailingNote
                    )
                }
            }
        }
    }

    /** How much of the row the name is allowed to shrink to before it is gone. */
    private fun assertNameIsReadable(what: String) {
        compose.onNodeWithText(name).assertIsDisplayed()
        val width = compose.onNodeWithText(name).getUnclippedBoundsInRoot()
            .let { it.right - it.left }
        assertTrue("$what left the name $width wide", width > 80.dp)
    }

    @Test
    fun theNameIsReadableWithNoTrailingNote() {
        showRow(trailingNote = null)
        assertNameIsReadable("a row with nothing on the right")
    }

    @Test
    fun aLongTrailingNoteCannotSqueezeOutTheName() {
        // The exact sentence that emptied the name off Largest files.
        showRow(trailingNote = "about 459 KB after optimising")
        assertNameIsReadable("the saving note")
    }

    @Test
    fun evenAnAbsurdTrailingNoteCannotSqueezeOutTheName() {
        // Nothing in the app says this much, but the row is a shared component
        // and the next caller does not have to know where the cliff is.
        showRow(trailingNote = "about 459 KB after optimising, which is most of it")
        assertNameIsReadable("an over-long note")
    }

    @Test
    fun theNameSurvivesOnANarrowPhone() {
        showRow(trailingNote = "about 459 KB after optimising", width = 320)
        assertNameIsReadable("the saving note on a 320 dp screen")
    }
}
