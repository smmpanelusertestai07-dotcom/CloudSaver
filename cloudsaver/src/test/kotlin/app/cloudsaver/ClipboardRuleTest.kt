package app.cloudsaver

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Copy-to-clipboard exists for one reason: a folder path has to be typed into
 * another app's picker, and a path typed slightly wrong backs up nothing while
 * looking correct. Everywhere else a copy button is decoration that competes
 * with the content, so the write lives in one helper and nothing else touches
 * the clipboard.
 */
class ClipboardRuleTest {

    private fun sourceRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/kotlin/app/cloudsaver")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `only one helper writes to the clipboard`() {
        val root = sourceRoot()
        assumeTrue("source directory not found", root != null)
        val writers = root!!.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.readText().contains("setClipEntry") }
            .map { it.name }
            .toList()
        assertEquals("clipboard writes belong in one place: $writers", 1, writers.size)
        assertEquals("Components.kt", writers.first())
    }

    @Test
    fun `nothing uses the deprecated clipboard manager`() {
        val root = sourceRoot()
        assumeTrue("source directory not found", root != null)
        val offenders = root!!.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.readText().contains("LocalClipboardManager") }
            .map { it.name }
            .toList()
        assertTrue("LocalClipboardManager is deprecated: $offenders", offenders.isEmpty())
    }
}
