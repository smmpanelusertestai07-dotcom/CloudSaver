package app.cloudsaver

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SQL LIKE has two silent failure modes, and this app hit one of them for a
 * whole release: a pattern written as `%\_keep.jpg` looks like it asks for a
 * literal underscore, but without an `ESCAPE` clause the backslash is just a
 * backslash and the underscore is still a wildcard - so the query asks for a
 * file name containing a backslash, no file has one, and it matches nothing.
 * It throws no error and logs nothing; the cleanup simply never ran.
 *
 * The other is the reverse: text typed by a person going into a pattern
 * unescaped, so a search for "IMG_2024" also finds "IMGx2024".
 *
 * Both are stated here because neither shows up in a crash, a lint warning or
 * a passing test of the code around them.
 */
class QueryRulesTest {

    private val main = File("src/main/kotlin/app/cloudsaver")

    private fun sources(): List<Pair<String, String>> =
        main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to withoutComments(it.readText()) }
            .toList()

    /** Prose may describe the trap; only code is judged for falling into it. */
    private fun withoutComments(text: String): String = text.lineSequence()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    @Test
    fun `an escaped wildcard is only written where the query says ESCAPE`() {
        // In Kotlin source a single backslash before a wildcard is written as
        // two characters, so that is what is searched for.
        val escaped = Regex("""\\\\[_%]""")
        val offenders = mutableListOf<String>()
        for ((name, text) in sources()) {
            for (hit in escaped.findAll(text)) {
                val from = maxOf(0, hit.range.first - 600)
                val to = minOf(text.length, hit.range.last + 600)
                val around = text.substring(from, to)
                // Db.Search builds the escaped text; the query it feeds names
                // the escape character, and that pairing is checked below.
                if (around.contains("ESCAPE") || around.contains("LIKE_ESCAPE")) continue
                offenders += "$name: ${hit.value}"
            }
        }
        assertTrue(
            "these escape a LIKE wildcard without a query that names an escape " +
                "character, so the pattern matches nothing at all: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `every query that names an escape character escapes what it is given`() {
        val db = File("src/main/kotlin/app/cloudsaver/data/db/Db.kt").readText()
        val queries = Regex("""ESCAPE\s+'\\\\'""").findAll(withoutComments(db)).count()
        assertTrue(
            "a query names an ESCAPE clause but nothing escapes the text it is " +
                "given, so the clause is decoration",
            queries == 0 || db.contains("fun escapeLike(")
        )
        // And the searching path uses it rather than passing raw text through.
        val callers = File("src/main/kotlin/app/cloudsaver")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "Db.kt" }
            .filter { withoutComments(it.readText()).contains("searchFlow(") }
            .map { it.name to withoutComments(it.readText()) }
            .toList()
        val unescaped = callers.filterNot { (_, text) -> text.contains("escapeLike(") }
        assertTrue(
            "these call the search query without escaping the typed text first, " +
                "so a name containing _ or % searches for something else: " +
                "${unescaped.map { it.first }}",
            unescaped.isEmpty()
        )
    }
}
