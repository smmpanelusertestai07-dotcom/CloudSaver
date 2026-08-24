package app.cloudsaver

import java.io.File
import org.junit.Assume.assumeTrue
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
        while (dir != null) {
            val candidate = File(dir, "app/src/main/kotlin/app/cloudsaver/ui/screens")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `every list screen has an empty state`() {
        val dir = screensDir()
        // Source is not on disk in every packaging of the test run; skipping is
        // honest, silently passing would not be.
        assumeTrue("screens source directory not found", dir != null)

        val offenders = dir!!.listFiles { f -> f.name.endsWith(".kt") }
            .orEmpty()
            .filter { it.readText().contains("LazyColumn") }
            .filterNot { it.readText().contains("EmptyState(") }
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
        assumeTrue("screens source directory not found", dir != null)
        val withLists = dir!!.listFiles { f -> f.name.endsWith(".kt") }
            .orEmpty()
            .count { it.readText().contains("LazyColumn") }
        // Guards against the check quietly passing because it found no files.
        assertTrue("expected list screens to exist, found $withLists", withLists >= 4)
    }
}
