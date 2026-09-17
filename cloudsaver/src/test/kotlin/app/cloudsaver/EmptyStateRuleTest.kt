package app.cloudsaver

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every scrolling list must have something to say when it is empty.
 *
 * A blank screen is indistinguishable from a broken one, and the screens here
 * are all reachable before there is any data at all - a fresh install opens on
 * empty lists. This is a source check rather than a UI test because it has to
 * fail when someone adds the twelfth list screen, not only when someone
 * happens to run that screen's test.
 */
class EmptyStateRuleTest {

    private fun screensDir(): File? {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        // Unit tests run from cloudsaver/, so the module-relative path is
        // the first thing to try; the walk upward is for a run started
        // from the repository root.
        File("src/main/kotlin/app/cloudsaver/ui/screens").let { if (it.exists()) return it }
        while (dir != null) {
            val candidate = File(dir, "cloudsaver/src/main/kotlin/app/cloudsaver/ui/screens")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `every list screen has an empty state`() {
        // Most screens no longer hold a LazyColumn themselves: they hand the
        // list to ListScreenScaffold, which draws the loading, empty and row
        // states in one place. So a screen counts as a list screen if it does
        // either, and it answers for its empty state itself or through the
        // frame it delegates to.
        val dir = screensDir()
        assertTrue("screens source directory not found", dir != null)
        val frame = File(dir!!.parentFile, "components/ListFramework.kt").readText()
        assertTrue(
            "the shared frame must say something when a list is empty",
            frame.contains("EmptyState(")
        )
        val offenders = dir.listFiles { f -> f.name.endsWith(".kt") }
            .orEmpty()
            .filter { it.readText().contains("LazyColumn") }
            .filterNot {
                val text = it.readText()
                text.contains("EmptyState(") || text.contains("ListScreenScaffold(")
            }
            .map { it.name }
        assertTrue(
            "These screens scroll a list but never say anything when it is " +
                "empty: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `the rule is actually checking something`() {
        val dir = screensDir()
        assertTrue("screens source directory not found", dir != null)
        val lists = dir!!.listFiles { f -> f.name.endsWith(".kt") }
            .orEmpty()
            .filter {
                val text = it.readText()
                text.contains("LazyColumn") || text.contains("ListScreenScaffold(")
            }
            .map { it.name }
            .toSet()
        // Named, not counted. A floor sits exactly on today's number and says
        // nothing the day a file is renamed: the rule above then checks four
        // screens instead of five and reports success either way. This one
        // went stale in precisely that fashion when the screens moved to the
        // shared frame, and reported nothing at all for a release.
        for (screen in listOf(
            "ActivityScreen.kt", "FilesScreen.kt", "FindSpaceScreens.kt",
            "KeptCopiesScreen.kt", "ReclaimScreen.kt"
        )) {
            assertTrue("$screen no longer appears to draw a list: $lists", screen in lists)
        }
    }
}
