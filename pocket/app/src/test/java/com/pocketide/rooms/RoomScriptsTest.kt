package com.pocketide.rooms

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Linux-side scripts (mcp.py, term.py, room.py, xdg-open, notify.py, the browser installer)
 * are tested by Python's own unittest, run here so they are part of every build.
 */
class RoomScriptsTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(300)

    @Test fun `the MCP server speaks both protocol eras over stdio`() = runPython("test_mcp")

    @Test fun `the terminal refuses strangers and runs a shell over its WebSocket`() = runPython("test_term")

    @Test fun `the room launcher and the phone clients behave`() = runPython("test_room_scripts")

    private fun runPython(module: String) {
        val python = listOf("python3", "/usr/bin/python3").firstOrNull(::works)
        assumeTrue("python3 is needed for the room script tests", python != null)
        val tests = File(TESTS)
        val process = ProcessBuilder(python, "-m", "unittest", "-v", module)
            .directory(tests)
            .redirectErrorStream(true)
            .apply {
                environment()["PYTHONDONTWRITEBYTECODE"] = "1"
                environment()["ROOMS_ASSETS"] = File(ASSETS).absolutePath
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(4, TimeUnit.MINUTES)
        if (!finished) process.destroyForcibly()
        assertEquals(output, 0, if (finished) process.exitValue() else -1)
    }

    private fun works(command: String): Boolean = try {
        val probe = ProcessBuilder(command, "--version").redirectErrorStream(true).start()
        probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0
    } catch (missing: java.io.IOException) {
        false
    }

    private companion object {
        const val TESTS = "src/test/java/com/pocketide/rooms/python"
        const val ASSETS = "src/main/assets/rooms"
    }
}
