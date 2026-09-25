package com.pocketide.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The scripts in assets/linux, checked with the tools this machine has: bash's parser,
 * shellcheck when installed, and launch.pl run for real with the machine's perl.
 */
class LinuxScriptsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val folder = listOf("src/main/assets/linux", "app/src/main/assets/linux").map(::File).first { it.isDirectory }

    private fun tool(name: String) = listOf("/usr/bin/$name", "/bin/$name").firstOrNull { File(it).canExecute() }

    private fun run(vararg argv: String, environment: Map<String, String> = emptyMap()): Pair<Int, String> {
        val builder = ProcessBuilder(*argv).redirectErrorStream(true)
        builder.environment().apply { clear(); putAll(environment) }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        return process.exitValue() to output
    }

    @Test
    fun everyScriptIsThere() {
        assertEquals(listOf("bootstrap.sh", "launch.pl", "update.sh"), folder.list()?.sorted())
    }

    @Test
    fun shellScriptsParseAndPassShellcheck() {
        val bash = tool("bash")
        assumeTrue(bash != null)
        for (script in listOf("bootstrap.sh", "update.sh")) {
            val path = File(folder, script).absolutePath
            val (code, output) = run(bash!!, "-n", path)
            assertEquals("$script: $output", 0, code)
            assertTrue(File(path).readText().startsWith("#!/bin/bash\n"))
            tool("shellcheck")?.let { shellcheck ->
                val (lint, report) = run(shellcheck, "--severity=style", path)
                assertEquals(report, 0, lint)
            }
        }
    }

    @Test
    fun theScriptsNeverPipeADownloadIntoAShell() {
        val pipeToShell = Regex("""(curl|wget)[^\n|]*\|\s*(sudo\s+)?(ba|z|da)?sh\b""")
        for (script in folder.listFiles().orEmpty()) {
            assertFalse(script.name, pipeToShell.containsMatchIn(script.readText()))
        }
    }

    @Test
    fun launchSetsTheVariablesFromItsFileAndDeletesIt() {
        val perl = tool("perl")
        val env = tool("env")
        assumeTrue(perl != null && env != null)
        val variables = temp.newFile("variables")
        val command = LinuxCommand(listOf("env"), env = mapOf("PASSWORD" to "per-launch", "MULTI" to "a\nb", "UID" to "7", "PATH" to "/usr/bin:/bin"))
        variables.writeBytes(ProotCommand.variables(command))
        val (code, output) = run(perl!!, File(folder, "launch.pl").absolutePath, variables.absolutePath, "env", environment = mapOf("HOME" to "/root", "PATH" to "/nowhere"))
        assertEquals(output, 0, code)
        assertEquals(setOf("HOME=/root", "MULTI=a", "b", "PASSWORD=per-launch", "PATH=/usr/bin:/bin", "UID=7"), output.lines().filter { it.isNotEmpty() }.toSet())
        assertFalse(variables.exists())
    }

    @Test
    fun launchRefusesABadNameAndAMissingProgram() {
        val perl = tool("perl")
        assumeTrue(perl != null)
        val launcher = File(folder, "launch.pl").absolutePath
        val bad = temp.newFile("bad").apply { writeBytes("lower=1\u0000".toByteArray()) }
        assertEquals(127, run(perl!!, launcher, bad.absolutePath, "true").first)
        val good = temp.newFile("good").apply { writeBytes("A=1\u0000".toByteArray()) }
        val (code, output) = run(perl, launcher, good.absolutePath, "/no/such/program")
        assertEquals(127, code)
        assertTrue(output, output.startsWith("launch.pl: cannot start /no/such/program"))
        assertEquals(127, run(perl, launcher, temp.root.absolutePath + "/missing", "true").first)
    }
}
