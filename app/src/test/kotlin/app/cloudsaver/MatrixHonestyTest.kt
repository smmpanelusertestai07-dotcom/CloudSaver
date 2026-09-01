package app.cloudsaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The verification matrix ships inside every release, so a row that cites a
 * test which does not exist is worse than a row with no citation at all: it
 * is a claim of proof where there is none. Twice in this project a row named
 * something the code did not have. This checks the matrix against the tree.
 */
class MatrixHonestyTest {

    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null) {
            if (File(dir, "RELEASE_MATRIX.md").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `every test named in the matrix exists`() {
        val root = repoRoot() ?: return
        val matrix = File(root, "RELEASE_MATRIX.md").readText()
        val named = Regex("""\b([A-Z][A-Za-z0-9]*Test)\b""").findAll(matrix)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("the matrix must cite its evidence", named.size > 20)
        val present = File(root, "app/src").walkTopDown()
            .filter { it.isFile && it.name.endsWith("Test.kt") }
            .map { it.name.removeSuffix(".kt") }
            .toSet()
        val absent = named - present
        assertTrue("these tests are cited but do not exist: $absent", absent.isEmpty())
    }

    @Test
    fun `every requirement has a row, and every row claims evidence`() {
        val root = repoRoot() ?: return
        val matrix = File(root, "RELEASE_MATRIX.md").readText()
        for (n in 1..20) {
            val id = "R%02d".format(n)
            assertTrue("$id must have a row", matrix.contains("| $id |"))
        }
        for (t in listOf("T1", "T2", "T3")) {
            assertTrue("$t must have a row", matrix.contains("| $t |"))
        }
        // No row may be marked done with an empty evidence cell.
        val rows = matrix.lineSequence().filter { it.startsWith("| R") || it.startsWith("| T") }
        for (row in rows) {
            // A markdown row starts and ends with the pipe, so the split has
            // an empty cell at each end; the evidence is the last real one.
            val cells = row.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            val id = cells.firstOrNull().orEmpty()
            assertTrue("row $id has four cells", cells.size >= 4)
            assertTrue(
                "row $id is marked done without evidence",
                cells.last().length > 40
            )
        }
    }

    /**
     * Word to number, for the counts the matrix states in prose.
     *
     * The matrix reads better with "sixteen layout rules" than with "16", so
     * the check has to read the words the same way a person does.
     */
    private val words = mapOf(
        "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        "twenty" to 20, "twenty-one" to 21, "twenty-two" to 22,
        "twenty-three" to 23, "twenty-four" to 24, "twenty-five" to 25
    )

    private fun countTests(target: File): Int =
        target.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .sumOf { f -> TEST_MARK.findAll(f.readText()).count() }

    private fun classesWithTests(dir: File): Int =
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .count { TEST_MARK.containsMatchIn(it.readText()) }

    /**
     * Every number the matrix states about itself is the number in the tree.
     *
     * The matrix opens by promising that nothing in it is asserted from
     * memory, and it ships inside every release - so a count that quietly
     * drifted is the document breaking its own first sentence. Five had: the
     * unit-test total, the layout-rule count in two places, the plural count,
     * the route count, and the size of the APK. Every one of them was true on
     * the day it was written, which is exactly why none of them was noticed
     * afterwards. Reading them off the tree costs a millisecond, and the
     * failure names both numbers so the fix is the smaller edit of the two.
     */
    @Test
    fun `every count the matrix states is the count in the tree`() {
        val root = repoRoot() ?: return
        val matrix = File(root, "RELEASE_MATRIX.md").readText()

        fun claimed(pattern: String, what: String): Int {
            val found = Regex(pattern).find(matrix)
            assertTrue("the matrix no longer states $what - update this rule", found != null)
            val raw = found!!.groupValues[1]
            return raw.toIntOrNull()
                ?: words[raw.lowercase()]
                ?: throw AssertionError("cannot read '$raw' as a number ($what)")
        }

        fun check(what: String, claim: Int, actual: Int) =
            assertEquals("the matrix states the wrong number of $what", actual, claim)

        val unit = File(root, "app/src/test")
        val instrumented = File(root, "app/src/androidTest")

        check(
            "unit tests",
            claimed("""\*\*(\d+) unit tests\*\*""", "its unit-test count"),
            countTests(unit)
        )
        check(
            "instrumented tests",
            claimed("""\*\*(\d+) instrumented tests across""", "its instrumented-test count"),
            countTests(instrumented)
        )
        check(
            "instrumented test classes",
            claimed("""instrumented tests across (\d+) classes""", "its test-class count"),
            classesWithTests(instrumented)
        )

        val layoutRules = countTests(
            File(root, "app/src/test/kotlin/app/cloudsaver/LayoutRulesTest.kt")
        )
        check(
            "layout rules",
            claimed("""([A-Za-z-]+) layout rules""", "its layout-rule count"),
            layoutRules
        )
        check(
            "layout rules where R13 cites them",
            claimed("""all ([a-z-]+) rules""", "its layout-rule count in R13"),
            layoutRules
        )

        val strings = File(root, "app/src/main/res/values/strings.xml").readText()
        check(
            "plurals",
            claimed("""(\d+) plurals all complete""", "its plural count"),
            Regex("<plurals").findAll(strings).count()
        )

        check(
            "FAQ answers",
            claimed("""(\d+) FAQ answers""", "its FAQ count"),
            Regex("""<string name="faq_a\d+"""").findAll(strings).count()
        )

        val app = File(root, "app/src/main/kotlin/app/cloudsaver/ui/App.kt").readText()
        check(
            "routes",
            claimed("""all ([a-z-]+) routes by tapping""", "its route count"),
            Regex("""composable\(Routes\.""").findAll(app).count()
        )
    }

    private companion object {
        /** A test is a function carrying the annotation on its own line. */
        val TEST_MARK = Regex("""^\s*@Test\b""", RegexOption.MULTILINE)
    }
}

