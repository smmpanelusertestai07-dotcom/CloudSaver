package com.pocketide.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RemoteControlTest {
    @Test
    fun `the room's agy starts Google's Remote Control, the name an argument never code`() {
        val argv = RemoteControl.startCommand("x\"; rm -rf / #")
        assertEquals(listOf("/bin/sh", "-c", RemoteControl.SCRIPT, "pocketide-remote-control", "x\"; rm -rf / #"), argv)
        assertTrue(RemoteControl.SCRIPT.contains("remote-control start --name \"\$1\""))
        assertTrue(RemoteControl.SCRIPT.contains("\$HOME/.gemini/bin/agy"))
        assertFalse("no port of it is opened for the bridge", RemoteControl.SCRIPT.contains("port"))
        assertEquals("https://antigravity.google.com", RemoteControl.DASHBOARD)
    }

    @Test
    fun `the script runs agy with only the name it was given`() {
        val home = createTempDirectory()
        val bin = java.io.File(home, ".gemini/bin").apply { mkdirs() }
        java.io.File(bin, "agy").apply {
            writeText("#!/bin/sh\nprintf '%s|' \"\$@\"\n")
            setExecutable(true)
        }
        val process = ProcessBuilder(RemoteControl.startCommand("My phone; echo hacked")).redirectErrorStream(true)
            .apply { environment()["HOME"] = home.absolutePath }
            .start()
        val said = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals("remote-control|start|--name|My phone; echo hacked|", said)
        home.deleteRecursively()
    }

    @Test
    fun `a port that answers a caller without a key keeps Remote Control off`() {
        val open = HttpAnswer(200, "<html>dashboard</html>")
        val refused = HttpAnswer(401, "unauthenticated")
        assertEquals(RemoteControl.OPEN_TO_OTHER_APPS, RemoteControl.problem(mapOf(4100 to refused, 4101 to open)))
        assertEquals(RemoteControl.OPEN_TO_OTHER_APPS, RemoteControl.problem(mapOf(4101 to HttpAnswer(302, ""))))
        assertEquals("a port that is not a web server cannot be checked", RemoteControl.CANNOT_CHECK, RemoteControl.problem(mapOf(4100 to null)))
        assertNull(RemoteControl.problem(mapOf(4100 to refused, 4102 to HttpAnswer(403, ""))))
        assertNull("no port at all: only the outbound tunnel", RemoteControl.problem(emptyMap()))
    }

    @Test
    fun `a 404 at the root proves nothing, as an RPC server answers so while its routes work`() {
        assertEquals(RemoteControl.CANNOT_CHECK, RemoteControl.problem(mapOf(4100 to HttpAnswer(404, "404 page not found"))))
        assertEquals(RemoteControl.CANNOT_CHECK, RemoteControl.problem(mapOf(4100 to HttpAnswer(405, ""))))
        assertEquals(RemoteControl.CANNOT_CHECK, RemoteControl.problem(mapOf(4100 to HttpAnswer(401, ""), 4101 to HttpAnswer(404, ""))))
    }

    @Test
    fun `a port other devices on the network can reach keeps Remote Control off, even one that asks for a key`() {
        val refused = HttpAnswer(401, "unauthenticated")
        assertEquals(RemoteControl.OPEN_TO_NETWORK, RemoteControl.problem(mapOf(4100 to refused), onNetwork = setOf(4100)))
        assertNull("a port elsewhere on the network is not the daemon's", RemoteControl.problem(mapOf(4100 to refused), onNetwork = setOf(8080)))
    }

    private fun createTempDirectory() = kotlin.io.path.createTempDirectory("rc-home").toFile()
}
