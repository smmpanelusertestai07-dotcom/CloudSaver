package app.cloudsaver

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Colour is named in one file and nowhere else.
 *
 * A screen that hard-codes a colour looks right in whichever theme its author
 * had open and wrong in the other one, and the failure is invisible until
 * somebody switches. The theme package owns every literal; everything else
 * asks the colour scheme.
 */
class ThemePurityTest {

    private fun sourceRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        // Unit tests run from cloudsaver/, so the module-relative path is
        // the first thing to try; the walk upward is for a run started
        // from the repository root.
        File("src/main/kotlin/app/cloudsaver").let { if (it.exists()) return it }
        while (dir != null) {
            val candidate = File(dir, "cloudsaver/src/main/kotlin/app/cloudsaver")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private fun sourcesOutsideTheme(root: File): List<File> =
        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filterNot { it.parentFile?.name == "theme" }
            .toList()

    @Test
    fun `no screen names a colour of its own`() {
        val root = sourceRoot()
        assertTrue("source directory not found", root != null)

        // Color(0xFF...) and the named constants alike: both bypass the theme.
        val literal = Regex("""Color\(0x|Color\.(White|Black|Red|Green|Blue|Yellow|Gray|LightGray|DarkGray|Magenta|Cyan)\b""")
        val offenders = sourcesOutsideTheme(root!!)
            .mapNotNull { file ->
                val hits = file.readLines()
                    .withIndex()
                    .filter { (_, line) -> literal.containsMatchIn(line) }
                    .map { (i, line) -> "${file.name}:${i + 1} ${line.trim()}" }
                if (hits.isEmpty()) null else hits
            }
            .flatten()

        assertTrue(
            "These name a colour outside the theme package:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `the check is actually looking at the app`() {
        val root = sourceRoot()
        assertTrue("source directory not found", root != null)
        val scanned = sourcesOutsideTheme(root!!).size
        assertTrue("expected to scan the app's sources, scanned $scanned", scanned >= 30)
    }
}
