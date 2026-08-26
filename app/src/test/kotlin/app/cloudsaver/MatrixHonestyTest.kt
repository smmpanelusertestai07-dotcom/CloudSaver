package app.cloudsaver

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
}
