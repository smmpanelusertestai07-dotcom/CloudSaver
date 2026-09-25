package com.pocketide.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The GitHub App holds Administration: write (to create repositories and switch Actions off in
 * the keyring), which would also allow deleting a repository or making it public. This gate
 * fails the build if any code could do either.
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
    fun `the GitHub client has no verb that deletes or edits a repository`() {
        assertEquals(listOf("GET", "POST", "PUT"), Verb.entries.map { it.name })
    }

    @Test
    fun `only the github package talks to the GitHub API`() {
        val outside = sources.filterNot { relative(it).startsWith("com/pocketide/github/") }
            // A network check may name the host to test that it is reachable; calling the REST API
            // means a URL with a scheme, which only the github package builds.
            .filter { text(it).contains("https://api.github.com") }
        assertEquals("GitHub API calls outside com.pocketide.github", emptyList<String>(), outside.map(::relative))
    }

    @Test
    fun `nothing in the github package can delete a repository or change its visibility`() {
        val github = sources.filter { relative(it).startsWith("com/pocketide/github/") }
        assertTrue(github.isNotEmpty())
        val forbidden = listOf(
            Regex("\"DELETE\""),
            Regex("\"PATCH\""),
            Regex("""\.delete\(\s*\)"""),
            Regex("""\.patch\("""),
            Regex("\"visibility\""),
            NOT_PRIVATE,
        )
        val hits = github.flatMap { file ->
            forbidden.filter { it.containsMatchIn(text(file)) }.map { "${relative(file)}: ${it.pattern}" }
        }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun `the gate itself catches what it is meant to catch`() {
        val samples = listOf(
            "Request.Builder().url(u).delete().build()",
            "put(\"private\", false)",
            "put(\"visibility\", \"public\")",
            "method(\"DELETE\", null)",
        )
        val patterns = listOf(
            Regex("""\.delete\(\s*\)"""),
            NOT_PRIVATE,
            Regex("\"visibility\""),
            Regex("\"DELETE\""),
        )
        samples.zip(patterns).forEach { (sample, pattern) -> assertTrue(sample, pattern.containsMatchIn(sample)) }
        assertTrue(NOT_PRIVATE.find("put(\"private\", true)") == null)
    }

    private companion object {
        /** `put("private", …)` with anything but `true`. */
        val NOT_PRIVATE = Regex("""put\(\s*"private"\s*,(?!\s*true\s*\))""")
    }
}
