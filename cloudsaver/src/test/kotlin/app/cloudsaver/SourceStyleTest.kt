package app.cloudsaver

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sources read as though one hand wrote them.
 *
 * Nothing here changes what the app does; all of it changes how long it
 * takes to read. A file whose imports arrive in the order someone happened
 * to need them hides the one import that matters, and two people editing the
 * same header produce a conflict over nothing. Sixty-eight files in this
 * module had drifted that way before this rule existed.
 */
class SourceStyleTest {

    private val sources = File("src").walkTopDown().filter { it.extension == "kt" }

    @Test
    fun `every file imports in one order`() {
        val unsorted = sources.filter { file ->
            val imports = file.readLines().filter { it.startsWith("import ") }
            imports != imports.sorted()
        }.map { it.name }.toList()
        assertTrue("these files import out of order: $unsorted", unsorted.isEmpty())
    }

    @Test
    fun `no file imports the same name twice`() {
        val duplicated = sources.mapNotNull { file ->
            val imports = file.readLines().filter { it.startsWith("import ") }
            val repeats = imports.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (repeats.isEmpty()) null else "${file.name}: $repeats"
        }.toList()
        assertTrue("$duplicated", duplicated.isEmpty())
    }
}
