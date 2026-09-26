package com.pocketide.builds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rules every workflow template must keep (plan §13, §16): started only by hand, read-only
 * token, every action pinned to a full commit SHA with its tag beside it, a time limit on every
 * job, artifacts kept 7 days, and Secrets named only by the release template.
 */
class TemplateGateTest {
    private val folder = File("src/main/assets/templates")
    private val files = folder.listFiles { f -> f.name.endsWith(".yml") }.orEmpty().sortedBy { it.name }

    private fun block(lines: List<String>, key: String): List<String> {
        val start = lines.indexOfFirst { it == "$key:" }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { it.isBlank() || it.startsWith(" ") }.filter { it.isNotBlank() }
    }

    @Test
    fun everyCatalogTemplateHasItsFileAndNoOthersExist() {
        assertEquals(TemplateCatalog.all.map { "${it.id}.yml" }.sorted(), files.map { it.name })
        assertEquals(9, files.size)
    }

    @Test
    fun onlyWorkflowDispatchStartsThem() {
        for (file in files) {
            val lines = file.readLines()
            val triggers = block(lines, "on").filter { Regex("^ {2}\\S").containsMatchIn(it) }.map { it.trim() }
            assertEquals("${file.name} triggers", listOf("workflow_dispatch:"), triggers)
            val text = file.readText()
            for (forbidden in listOf("pull_request", "push:", "schedule:", "workflow_run", "repository_dispatch")) {
                assertTrue("${file.name} must not use $forbidden", !text.contains(forbidden))
            }
        }
    }

    @Test
    fun theTokenOnlyReadsTheRepository() {
        for (file in files) {
            val lines = file.readLines()
            assertEquals("${file.name} permissions", listOf("  contents: read"), block(lines, "permissions"))
            assertTrue("${file.name} must not widen permissions in a job", lines.count { it.trim() == "permissions:" } == 1)
            assertTrue("${file.name} must not keep the token on disk", lines.filter { it.contains("actions/checkout@") }.isNotEmpty())
            assertEquals(
                "${file.name} checkout must drop credentials",
                lines.count { it.contains("actions/checkout@") },
                lines.count { it.trim() == "persist-credentials: false" },
            )
        }
    }

    @Test
    fun everyActionIsPinnedToAFullCommitWithItsTag() {
        val pinned = Regex("^\\s*(- )?uses: [A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(/[A-Za-z0-9_./-]+)?@[0-9a-f]{40} # v\\d+(\\.\\d+){0,2}$")
        for (file in files) {
            val uses = file.readLines().filter { it.contains("uses:") }
            assertTrue("${file.name} uses actions", uses.isNotEmpty())
            for (line in uses) assertTrue("${file.name}: $line", pinned.matches(line))
        }
    }

    @Test
    fun theSameActionIsPinnedToTheSameCommitEverywhere() {
        val pins = files.flatMap { it.readLines() }.filter { it.contains("uses:") }
            .map { it.substringAfter("uses: ").substringBefore(" #") }
            .groupBy({ it.substringBefore('@') }, { it.substringAfter('@') })
        for ((action, shas) in pins) assertEquals("$action pins", 1, shas.toSet().size)
    }

    /**
     * Dependabot keeps PocketIDE's own workflow current, but it cannot see these files inside
     * the app. An action both use must carry the same pin, so its update reaches the templates.
     */
    @Test
    fun actionsSharedWithPocketIdesWorkflowFollowItsPins() {
        fun pins(lines: List<String>) = lines.filter { it.contains("uses:") }
            .map { it.substringAfter("uses: ").substringBefore(" #").trim() }
            .associate { it.substringBefore('@') to it.substringAfter('@') }
        val own = pins(File("../../.github/workflows/pocket.yml").readLines())
        val templates = pins(files.flatMap { it.readLines() })
        val shared = templates.keys.intersect(own.keys)
        assertTrue("the templates share checkout with the workflow", "actions/checkout" in shared)
        for (action in shared) assertEquals("$action pin", own[action], templates[action])
    }

    @Test
    fun everyJobHasATimeLimitAndArtifactsExpire() {
        for (file in files) {
            val lines = file.readLines()
            val jobs = block(lines, "jobs").count { Regex("^ {2}[A-Za-z0-9_-]+:$").matches(it) }
            val limits = lines.count { Regex("^ {4}timeout-minutes: \\d+$").matches(it) }
            assertEquals("${file.name} time limits", jobs, limits)
            val uploads = lines.count { it.contains("actions/upload-artifact@") }
            assertTrue("${file.name} uploads results", uploads > 0)
            assertEquals("${file.name} retention", uploads, lines.count { it.trim() == "retention-days: 7" })
            assertTrue("${file.name} cancels a superseded run", lines.contains("  cancel-in-progress: true"))
        }
    }

    @Test
    fun onlyTheReleaseTemplateNamesSecrets() {
        for (file in files) {
            val text = file.readText()
            val names = Regex("secrets\\.([A-Z0-9_]+)").findAll(text).map { it.groupValues[1] }.toSet()
            if (file.name == "android-release.yml") {
                assertEquals(setOf("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"), names)
                val reads = EXPRESSION.findAll(text).map { it.groupValues[1].trim() }.filter { SECRETS_CONTEXT.containsMatchIn(it) }.toList()
                assertTrue("${file.name} reads each Secret by its name only: $reads", reads.all { NAMED_SECRET.matches(it) })
            } else {
                assertTrue("${file.name} must not read Secrets", !readsSecrets(text))
            }
            assertTrue("${file.name} must not use GITHUB_TOKEN", !text.contains("GITHUB_TOKEN"))
        }
    }

    /**
     * Anything the repository's code runs in a job can change what that job's later steps run, so
     * a job given Secrets must not check the repository out or build it; and Secrets given before
     * `jobs:` reach every job, the build too.
     */
    @Test
    fun secretsReachOnlyAJobThatNeverRunsTheRepositorysCode() {
        val jobHeader = Regex("^ {2}[A-Za-z0-9_-]+:$")
        for (file in files) {
            val lines = file.readLines()
            val top = lines.take(lines.indexOf("jobs:")).joinToString("\n")
            assertTrue("${file.name}: Secrets set before jobs: reach every job", !readsSecrets(top))
            val jobs = lines.drop(lines.indexOf("jobs:") + 1)
            val starts = jobs.indices.filter { jobHeader.matches(jobs[it]) }
            for ((index, start) in starts.withIndex()) {
                val job = jobs.subList(start, starts.getOrElse(index + 1) { jobs.size }).joinToString("\n")
                if (!readsSecrets(job)) continue
                val name = "${file.name} ${jobs[start].trim()}"
                assertTrue("$name must not check out the repository", !job.contains("actions/checkout@"))
                assertTrue("$name must not run the repository's build", !job.contains("gradlew"))
            }
        }
        val release = File(folder, "android-release.yml").readText()
        assertTrue("the release template still signs", release.contains("secrets.ANDROID_KEYSTORE_BASE64"))
    }

    /** A template that reads Secrets is one only the owner may start, never an agent's run_build. */
    @Test
    fun onlyTheTemplatesThatReadSecretsAreMarkedSo() {
        for (template in TemplateCatalog.all) {
            assertEquals(template.id, readsSecrets(File(folder, "${template.id}.yml").readText()), template.usesSecrets)
        }
    }

    @Test
    fun everyFormOfTheSecretsContextIsSeen() {
        listOf(
            "run: echo \${{ secrets.KEY }}", "run: echo \"\${{ toJSON(secrets) }}\"", "run: echo \"\${{ secrets['KEY'] }}\"",
            "run: echo \${{ fromJSON(toJSON( secrets )).KEY }}", "secrets: inherit", "if: secrets.KEY != ''",
        ).forEach { assertTrue(it, readsSecrets("    steps:\n      - $it\n")) }
        listOf("run: cat docs/secrets.md", "run: echo \${{ inputs.secrets }}", "run: echo \${{ steps.secrets.outputs.x }}")
            .forEach { assertTrue(it, !readsSecrets("    steps:\n      - $it\n")) }
    }

    /**
     * Whether [text] reads the secrets context in any form (secrets.NAME, secrets['NAME'],
     * toJSON(secrets), in a `${{ }}` expression or an `if:`) or passes Secrets on to another
     * workflow (`secrets: inherit`): the rule tools/gates/templates.py checks.
     */
    private fun readsSecrets(text: String): Boolean =
        (EXPRESSION.findAll(text) + CONDITION.findAll(text)).any { SECRETS_CONTEXT.containsMatchIn(it.groupValues[1]) } ||
            PASSES_SECRETS.containsMatchIn(text)

    private companion object {
        val EXPRESSION = Regex("\\$\\{\\{([\\s\\S]*?)\\}\\}")
        val CONDITION = Regex("(?m)^\\s*(?:- )?if:(.*)$")
        val SECRETS_CONTEXT = Regex("(?<![\\w.])secrets\\b")
        val NAMED_SECRET = Regex("secrets\\.[A-Z0-9_]+")
        val PASSES_SECRETS = Regex("(?m)^\\s*(?:- )?secrets\\s*:")
    }

    @Test
    fun workflowNamesMatchTheCatalog() {
        for (template in TemplateCatalog.all) {
            val name = File(folder, "${template.id}.yml").readLines().first { it.startsWith("name: ") }.removePrefix("name: ")
            assertEquals(template.workflowName, name)
            assertEquals("pocketide-${template.id}.yml", template.fileName)
        }
    }

    @Test
    fun macAndWindowsCostsAreSaid() {
        for (template in TemplateCatalog.all) {
            when (template.minutesMultiplier) {
                10 -> assertTrue(template.id, template.description.contains("10 minutes"))
                2 -> assertTrue(template.id, template.description.contains("2 minutes"))
                else -> assertEquals(1, template.minutesMultiplier)
            }
        }
        assertEquals(10, TemplateCatalog.find(TemplateCatalog.IOS_SIMULATOR)!!.minutesMultiplier)
        assertTrue(File(folder, "ios-simulator.yml").readText().contains("ten minutes"))
    }
}
