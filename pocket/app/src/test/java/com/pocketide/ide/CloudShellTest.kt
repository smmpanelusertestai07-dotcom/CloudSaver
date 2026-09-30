package com.pocketide.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest

class CloudShellTest {
    private val script: File by lazy {
        listOf("../..", "..", ".").map { File(it, CloudShell.SCRIPT_PATH) }.first { it.isFile }
    }

    @Test
    fun `the command runs the script only when it matches the SHA-256 this app pins`() {
        val sha = MessageDigest.getInstance("SHA-256").digest(script.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("pin CloudShell.SCRIPT_SHA256 to the script in this repository", sha, CloudShell.SCRIPT_SHA256)
        val command = CloudShell.setupCommand
        assertTrue(command.contains(CloudShell.SCRIPT_URL))
        assertTrue(CloudShell.SCRIPT_URL.contains("/${CloudShell.SCRIPT_COMMIT}/"))
        assertTrue(command.contains("\"${CloudShell.SCRIPT_SHA256}  \$HOME/pocketide-cloudshell.sh\" | sha256sum -c -"))
        assertTrue("checked before it runs", command.indexOf("sha256sum -c") < command.indexOf("bash ~/pocketide-cloudshell.sh"))
    }

    @Test
    fun `Cloud Shell opens after Google's account chooser`() {
        val url = CloudShell.chooseAccountThen(CloudShell.TERMINAL)
        assertTrue(url.startsWith("https://accounts.google.com/AccountChooser?continue="))
        assertEquals(CloudShell.TERMINAL, URLDecoder.decode(url.substringAfter("continue="), "UTF-8"))
    }

    @Test
    fun `the script keeps to the home folder and to Google's rules`() {
        val text = script.readText()
        assertTrue(text.contains("PORT=${CloudShell.PORT}"))
        listOf("sudo ", "apt-get", "while true", "keepalive", "keep-alive").forEach { assertTrue("script uses $it", !text.contains(it)) }
    }
}
