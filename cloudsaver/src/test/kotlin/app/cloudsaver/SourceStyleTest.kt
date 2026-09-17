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

    /**
     * An import nothing uses is a line that says the file needs something it
     * does not.
     *
     * Kotlin does not warn about them, so they accumulate quietly: deleting a
     * screen leaves its imports behind, and the next reader has to work out
     * which of them still matter. Seventy-three were left across this module
     * before this rule existed, thirteen of them in the one file a release
     * had just gutted.
     *
     * A name is "used" when it appears anywhere after the imports, comments
     * included - a rule that under-reports rather than deleting a line the
     * compiler wanted. The exception is the convention names: `by` needs
     * getValue and setValue imported and never writes either word, and the
     * operators below are the same shape.
     */
    @Test
    fun `every import is used`() {
        val convention = setOf(
            "getValue", "setValue", "provideDelegate", "invoke", "iterator", "next",
            "hasNext", "compareTo", "contains", "rangeTo", "get", "set", "equals",
            "hashCode", "toString", "plus", "minus", "times", "div", "rem",
            "unaryPlus", "unaryMinus", "inc", "dec", "not"
        ) + (1..7).map { "component$it" }
        val dead = sources.flatMap { file ->
            val lines = file.readLines()
            val last = lines.indexOfLast { it.startsWith("import ") }
            if (last < 0) return@flatMap emptyList()
            val body = lines.drop(last + 1).joinToString("\n")
            lines.take(last + 1).filter { it.startsWith("import ") }.mapNotNull { line ->
                val path = line.removePrefix("import ").trim()
                if (path.endsWith(".*")) return@mapNotNull null
                val name = path.substringAfter(" as ", path.substringAfterLast('.'))
                if (name in convention) return@mapNotNull null
                if (Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(body)) null
                else "${file.name}: $path"
            }
        }.toList()
        assertTrue("nothing uses these: $dead", dead.isEmpty())
    }
}
