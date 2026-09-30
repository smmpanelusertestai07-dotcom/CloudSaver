package com.pocketide.cloudshell

import com.pocketide.agents.Agent
import com.pocketide.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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
    fun `each agent's port and projects folder are the ones the script sets up`() {
        val agents = Regex("AGENTS=\"([^\"]+)\"").find(script.readText())?.groupValues?.get(1)
        val entries = checkNotNull(agents) { "the script names its agents" }.split(" ").map { it.split(":") }
        assertEquals(Agent.entries.size, entries.size)
        Agent.entries.forEach { agent ->
            val (key, port, extension) = entries.single { it[2].replace("/", ".") == agent.extensionId }
            assertEquals(agent.name, CloudShell.port(agent), port.toInt())
            assertEquals(agent.name, "~/projects/$key", CloudShell.projects(agent))
            assertTrue(extension.isNotBlank())
        }
        assertEquals("ports are each agent's own", Agent.entries.size, Agent.entries.map(CloudShell::port).distinct().size)
    }

    @Test
    fun `each agent's VS Code opens its agent full screen`() {
        val text = script.readText()
        assertTrue(text.contains("\"pocketide.agent\": agent"))
        // The layout extension knows every agent the script sets up, by the same key.
        val layout = text.substringAfter("const AGENTS = {").substringBefore("};")
        val keys = Regex("AGENTS=\"([^\"]+)\"").find(text)!!.groupValues[1].split(" ").map { it.substringBefore(":") }
        keys.forEach { key -> assertTrue(key, layout.contains("'$key':") || layout.contains("\n  $key:")) }
        assertTrue(text.contains("\"workbench.secondarySideBar.defaultVisibility\""))
    }

    @Test
    fun `the script keeps to the home folder and to Google's rules`() {
        val text = script.readText()
        // Only root's start-up hook uses sudo, and only to drop to the owner's own account.
        Regex("\\bsudo\\b[^\\n]*").findAll(text).map { it.value }.forEach { assertTrue(it, it.startsWith("sudo -u ")) }
        listOf("apt-get", "apt install", "while true", "sleep infinity", "keepalive", "keep-alive", "xmrig", "nmap", "masscan", "0.0.0.0")
            .forEach { assertFalse("the script uses $it", text.contains(it)) }
        assertFalse("nothing downloaded runs unchecked", Regex("(curl|wget)[^\\n|]*\\|\\s*(ba)?sh").containsMatchIn(text))
        assertTrue("VS Code listens only inside Cloud Shell", text.contains("--bind-addr \"127.0.0.1:"))
    }

    @Test
    fun `the set-up is asked for until it is done, and again before Google deletes an unused home folder`() {
        val day = 24 * 60 * 60 * 1000L
        val now = 1_000 * day
        assertTrue(CloudShell.needsSetUp(Settings(), now))
        assertTrue("an account alone is not a set-up", CloudShell.needsSetUp(Settings(cloudAccount = "dev@example.com"), now))
        val done = Settings(cloudAccount = "dev@example.com", cloudSetUpAt = now - 200 * day, cloudOpenedAt = now - 3 * day)
        assertFalse(CloudShell.needsSetUp(done, now))
        assertEquals(3, CloudShell.daysUnused(done, now))
        val unused = done.copy(cloudOpenedAt = now - CloudShell.ASK_AGAIN_AFTER_DAYS * day)
        assertTrue(CloudShell.needsSetUp(unused, now))
        assertTrue(CloudShell.ASK_AGAIN_AFTER_DAYS < CloudShell.DELETED_AFTER_DAYS)
    }

    @Test
    fun `a set-up made with another script is offered again, one made with this one is not`() {
        assertTrue(CloudShell.newerSetUp(Settings(cloudScript = "7210b5ab30b32b3ffa8710d2a6a1a5b281ab2f7f")))
        assertTrue("set up before PocketIDE kept the script", CloudShell.newerSetUp(Settings()))
        assertFalse(CloudShell.newerSetUp(Settings(cloudScript = CloudShell.SCRIPT_COMMIT)))
    }

    @Test
    fun `every address opens with the picked account`() {
        val account = "dev+pocket@example.com"
        val encoded = "dev%2Bpocket%40example.com"
        assertEquals("https://shell.cloud.google.com/?show=terminal&authuser=$encoded", CloudShell.terminal(account))
        assertTrue(CloudShell.editor(account).endsWith("&authuser=$encoded"))
        assertEquals("https://console.cloud.google.com/?authuser=$encoded", CloudShell.console(account))
        Agent.entries.forEach { agent ->
            val url = CloudShell.vsCode(agent, account)
            assertTrue(url, url.startsWith("https://ssh.cloud.google.com/devshell/proxy?authuser=$encoded&port=${CloudShell.port(agent)}&"))
        }
        assertEquals("no account, no authuser", "https://shell.cloud.google.com/?show=terminal", CloudShell.terminal(""))
    }
}
