package com.pocketide.ui.shell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A plain clickable does not grow to Material's 48 dp touch target. The two shapes that slipped
 * through before: a fixed small size made clickable, and a clickable wrapped tightly around a
 * status chip (about 22 dp tall).
 */
class TouchTargetsTest {
    private val smallAndClickable = Regex("""\.size\((\d+)\.dp\)[^\n]*\.clickable""")
    private val clickableChip = Regex("""clickable[^\n]*\{\s*StatusChip\(""")

    @Test
    fun tappableThingsAreAtLeastFortyEightDp() {
        val found = File("src/main/java/com/pocketide/ui").walk().filter { it.extension == "kt" }.flatMap { file ->
            val text = file.readText()
            val small = smallAndClickable.findAll(text).filter { it.groupValues[1].toInt() < MIN_TOUCH_DP }
            (small + clickableChip.findAll(text)).map { "${file.name}: ${it.value.take(80)}" }
        }.toList()
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    private companion object {
        const val MIN_TOUCH_DP = 48
    }
}
