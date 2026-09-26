package com.pocketide.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The GitHub App holds Administration: write (to create repositories), which would also allow
 * deleting a repository or making it public. This gate fails the build if any code could do
 * either, and keeps the two write verbs to the one use each has.
 */
class LeastPrivilegeGateTest {
    private val sources: List<File> = listOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private fun text(file: File) = file.readText()

    private fun relative(file: File) = file.invariantSeparatorsPath.substringAfter("java/")

    @Test
    fun `the GitHub client's verbs are the reviewed five`() {
        assertEquals(listOf("GET", "POST", "PUT", "PATCH", "DELETE"), Verb.entries.map { it.name })
    }

    @Test
    fun `DELETE is used only to delete a cloud computer`() {
        val users = sources.filter { text(it).contains("Verb.DELETE") }.map(::relative)
        assertEquals(listOf("com/pocketide/cloud/CodespacesRest.kt"), users)
        val line = sources.single { text(it).contains("Verb.DELETE") }.readLines().single { "Verb.DELETE" in it }
        assertTrue(line, line.contains("\"user\", \"codespaces\""))
    }

    @Test
    fun `PATCH only moves a branch, and never with force`() {
        val users = sources.filter { text(it).contains("Verb.PATCH") }
        assertEquals(listOf("com/pocketide/github/GitHubRestApi.kt"), users.map(::relative))
        val source = text(users.single())
        val call = source.lines().single { "Verb.PATCH" in it }
        assertTrue(call, call.contains("\"git\", \"refs\", \"heads\""))
        assertTrue(source.contains("put(\"force\", false)"))
        assertTrue(!source.contains("put(\"force\", true)"))
    }

    @Test
    fun `only the github package names the GitHub API`() {
        val outside = sources.filterNot { relative(it).startsWith("com/pocketide/github/") }
            .filter { text(it).contains("https://api.github.com") }
        assertEquals("GitHub API calls outside com.pocketide.github", emptyList<String>(), outside.map(::relative))
    }

    @Test
    fun `nothing that talks to GitHub can delete a repository or change its visibility`() {
        val gitHubFacing = sources.filter { relative(it).startsWith("com/pocketide/github/") || relative(it).startsWith("com/pocketide/cloud/") }
        assertTrue(gitHubFacing.isNotEmpty())
        val forbidden = listOf(Regex("\"visibility\""), Regex("""\.delete\(\s*\)"""), Regex("""\.patch\("""), NOT_PRIVATE, Regex("\"archived\""))
        val hits = gitHubFacing.flatMap { file ->
            forbidden.filter { it.containsMatchIn(text(file)) }.map { "${relative(file)}: ${it.pattern}" }
        }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun `the gate itself catches what it is meant to catch`() {
        val samples = listOf("Request.Builder().url(u).delete().build()", "put(\"private\", false)", "put(\"visibility\", \"public\")")
        val patterns = listOf(Regex("""\.delete\(\s*\)"""), NOT_PRIVATE, Regex("\"visibility\""))
        samples.zip(patterns).forEach { (sample, pattern) -> assertTrue(sample, pattern.containsMatchIn(sample)) }
        assertTrue(NOT_PRIVATE.find("put(\"private\", true)") == null)
    }

    private companion object {
        /** `put("private", …)` with anything but `true`. */
        val NOT_PRIVATE = Regex("""put\(\s*"private"\s*,(?!\s*true\s*\))""")
    }
}
