package app.cloudsaver.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the five list screens behaving the same way.
 *
 * The framework makes it possible for them to agree; it does not make it
 * automatic. A screen can pass a `selection` and a `matchingCount` to the
 * scaffold, get the "Select all" affordance and the action bar for free, and
 * still have no gesture that starts a selection - which is exactly what
 * happened to Biggest files and Kept light copies: the checkbox appeared only
 * once a selection existed, and nothing could create one, so the action bar
 * was unreachable. It reads as finished in the diff and is dead on the phone.
 */
class ListConsistencyTest {

    private val screens = File("src/main/kotlin/app/cloudsaver/ui/screens")

    private fun source(name: String): String {
        val file = screens.resolve(name)
        assertTrue("$name must exist", file.isFile)
        return file.readText()
    }

    /** Screen file, and the composable inside it that owns the list. */
    private val listScreens = listOf(
        "FilesScreen.kt" to "FilesScreen",
        "FindSpaceScreens.kt" to "DuplicatesScreen",
        "FindSpaceScreens.kt" to "BiggestFilesScreen",
        "KeptCopiesScreen.kt" to "KeptCopiesScreen"
    )

    /**
     * The body of one composable: from its declaration to the next top-level
     * declaration. Good enough to tell whether the gesture is wired inside
     * that screen rather than merely present elsewhere in the file.
     */
    private fun bodyOf(fileName: String, function: String): String {
        val text = source(fileName)
        val start = text.indexOf("fun $function(")
        assertTrue("$function must exist in $fileName", start >= 0)
        val rest = text.substring(start)
        val next = Regex("""\n(@Composable\n)?(private )?fun \w+\(""")
            .find(rest, startIndex = 1)
        return if (next == null) rest else rest.substring(0, next.range.first)
    }

    @Test
    fun everyListScreenUsesTheSharedScaffold() {
        for ((file, function) in listScreens) {
            assertTrue(
                "$function must build its list with ListScreenScaffold",
                bodyOf(file, function).contains("ListScreenScaffold(")
            )
        }
    }

    @Test
    fun everyListWithASelectionCanStartOne() {
        for ((file, function) in listScreens) {
            val body = bodyOf(file, function)
            if (!body.contains("rememberListSelection()")) continue
            assertTrue(
                "$function offers a selection but nothing starts one: it needs " +
                    "a long-press wired to selection.toggle",
                body.contains("onLongPress")
            )
        }
    }

    /**
     * Offering "Select all" and then having nothing to do with the selection
     * is the same dead end from the other direction.
     */
    @Test
    fun everyListWithASelectionCanActOnIt() {
        for ((file, function) in listScreens) {
            val body = bodyOf(file, function)
            if (!body.contains("rememberListSelection()")) continue
            assertTrue(
                "$function offers a selection but no action bar to act on it",
                body.contains("actionBar")
            )
        }
    }

    /** Every list has to say what to do when its filters match nothing. */
    @Test
    fun everyListDistinguishesEmptyFromFilteredEmpty() {
        for ((file, function) in listScreens) {
            val body = bodyOf(file, function)
            assertTrue(
                "$function must tell \"nothing here\" apart from " +
                    "\"nothing matches these filters\"",
                body.contains("FilteredEmptyState")
            )
            assertTrue(
                "$function must answer a search that matched nothing",
                body.contains("SearchEmptyState")
            )
        }
    }

    /** Y1.2: Type is on every list screen, and it is the first chip. */
    @Test
    fun everyListOffersTheTypeFilterFirst() {
        for ((file, function) in listScreens) {
            val body = bodyOf(file, function)
            val filters = body.indexOf("filters = listOf(")
            assertTrue("$function must declare a filter list", filters >= 0)
            val firstEntry = body.substring(filters, minOf(filters + 200, body.length))
            assertTrue(
                "$function must lead its filter row with the Type filter",
                firstEntry.contains("typeFilter(")
            )
        }
    }
}
