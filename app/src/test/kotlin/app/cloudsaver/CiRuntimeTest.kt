package app.cloudsaver

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The workflows must keep running on GitHub's runners without anyone
 * watching them.
 *
 * GitHub removes the Node 20 runtime from its runners on 23 September 2026.
 * Every JavaScript action pinned to a major that still says `using: node20`
 * stops loading that day, and a workflow that cannot load its checkout step
 * cannot even begin - CI goes red on a repository nobody changed. The
 * majors below are the first of each action to declare `node24`, read from
 * each action.yml at its tag; anything older is a pin that expires.
 */
class CiRuntimeTest {

    /** Unit tests run from app/; the workflows live beside it, one level up. */
    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null) {
            if (File(dir, ".github/workflows").isDirectory) return dir
            dir = dir.parentFile
        }
        return null
    }

    private val workflows = File(repoRoot() ?: File("."), ".github/workflows")

    /** Action -> the lowest major whose action.yml runs on Node 24. */
    private val nodeTwentyFour = mapOf(
        "actions/checkout" to 5,
        "actions/setup-java" to 5,
        "actions/upload-artifact" to 6,
        "actions/download-artifact" to 7,
        "gradle/actions/setup-gradle" to 5,
        "android-actions/setup-android" to 4,
        "reactivecircus/android-emulator-runner" to 2
    )

    @Test
    fun `no action is pinned to a major that loses its runtime`() {
        assertTrue("the workflows directory must be found from app/", workflows.isDirectory)
        val uses = Regex("""uses:\s*([A-Za-z0-9_.\-]+/[A-Za-z0-9_.\-/]+)@v(\d+)""")
        val expiring = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        for (file in workflows.listFiles { f -> f.extension == "yml" || f.extension == "yaml" }.orEmpty()) {
            file.readLines().forEachIndexed { idx, line ->
                val m = uses.find(line) ?: return@forEachIndexed
                val (action, major) = m.destructured
                val floor = nodeTwentyFour[action]
                if (floor == null) {
                    unknown += "${file.name}:${idx + 1} $action"
                } else if (major.toInt() < floor) {
                    expiring += "${file.name}:${idx + 1} $action@v$major (needs v$floor)"
                }
            }
        }
        assertTrue(
            "these actions are pinned below the first Node 24 major and stop " +
                "loading on 2026-09-23: $expiring",
            expiring.isEmpty()
        )
        assertTrue(
            "new actions must be added to the Node 24 table with the major " +
                "whose action.yml says `using: node24`: $unknown",
            unknown.isEmpty()
        )
    }
}
