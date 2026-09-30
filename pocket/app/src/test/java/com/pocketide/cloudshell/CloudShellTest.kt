package com.pocketide.cloudshell

import com.pocketide.agents.Agent
import com.pocketide.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

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
        val text = script.readText()
        val agents = Regex("AGENTS=\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        val entries = checkNotNull(agents) { "the script names the agents it gives a VS Code" }.split(" ").map { it.split(":") }
        assertEquals(Agent.entries.size, entries.size)
        Agent.entries.forEach { agent ->
            val (key, port, extension) = entries.single { it[2].replace("/", ".") == agent.extensionId }
            assertEquals(agent.name, CloudShell.port(agent), port.toInt())
            assertEquals(agent.name, "~/projects/$key", CloudShell.projects(agent))
            assertTrue(extension.isNotBlank())
        }
        val returns = Regex("PORT = int\\(sys.argv\\[1]\\) if len\\(sys.argv\\) > 1 else (\\d+)").find(text)
        assertEquals(SignInReturn.PORT, checkNotNull(returns) { "the bridge's sign-in return port" }.groupValues[1].toInt())
        assertTrue(text.contains("BRIDGE_PORT=${SignInReturn.PORT}"))
        val ports = Agent.entries.map(CloudShell::port) + SignInReturn.PORT
        assertEquals("ports are each agent's own", ports.size, ports.distinct().size)
    }

    @Test
    fun `the launcher takes PocketIDE's door, and only an address the app gives`() {
        val text = script.readText()
        assertTrue(text.contains("proxy-uri) proxy_uri \"\${2:-}\" ;;"))
        val check = "grep -Eq '^http://\\{\\{port\\}\\}-[0-9a-f]{32}\\.localhost:[0-9]{2,5}/\$'"
        assertTrue("the launcher checks the address's shape: $check", text.contains(check))
        // The address Link gives has that shape.
        val template = "http://{{port}}-0123456789abcdef0123456789abcdef.localhost:40123/"
        assertTrue(Regex("^http://\\{\\{port\\}\\}-[0-9a-f]{32}\\.localhost:[0-9]{2,5}/$").matches(template))
        assertTrue("the door's address is private to the owner", text.contains("umask 077"))
        assertTrue("code-server starts with it", text.contains("export VSCODE_PROXY_URI=\"\$proxy\""))
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
            val url = CloudShell.screen(agent, account)
            assertTrue(url, url.startsWith("https://ssh.cloud.google.com/devshell/proxy?authuser=$encoded&port=${CloudShell.port(agent)}&"))
        }
        assertEquals("no account, no authuser", "https://shell.cloud.google.com/?show=terminal", CloudShell.terminal(""))
    }

    @Test
    fun `each agent opens its own VS Code in Chrome too`() {
        Agent.entries.forEach { agent ->
            assertTrue(agent.name, CloudShell.screen(agent, "a@b.c").contains("&port=${CloudShell.port(agent)}&cloudshell_retry=true&devshellProxyPath=%2F&"))
        }
    }

    @Test
    fun `a sign-in page that ended at localhost goes back to Cloud Shell through the bridge`() {
        val back = SignInReturn.address("http://localhost:1455/auth/callback?code=ac_1%2F2&state=xyz", "dev@example.com")
        val path = "/auth/callback?code=ac_1%2F2&state=xyz"
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray())
        assertEquals(
            "https://ssh.cloud.google.com/devshell/proxy?authuser=dev%40example.com&port=8090&cloudshell_retry=true" +
                "&devshellProxyPath=%2Fpocketide%2Fcallback%2F1455%2F$token&environment_name=default&environment_id=default",
            back,
        )
        assertEquals(path, String(Base64.getUrlDecoder().decode(token)))
        assertTrue(checkNotNull(SignInReturn.address("http://127.0.0.1:46831/", "a@b.c")).contains("%2Fcallback%2F46831%2F"))
        assertTrue(checkNotNull(SignInReturn.address("http://localhost:46831", "a@b.c")).contains("%2Fcallback%2F46831%2F"))
    }

    @Test
    fun `Antigravity's sign-in page names the phone port its return comes to`() {
        val page = "https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=1.apps.googleusercontent.com" +
            "&redirect_uri=http%3A%2F%2Flocalhost%3A34321%2Fauth%2Fcallback&response_type=code&state=s"
        assertEquals(34321, SignInReturn.pagePort(page))
        listOf(
            page.replace("https://", "http://"),
            page.replace("localhost%3A34321", "example.com%3A34321"),
            page.replace("localhost%3A34321", "localhost%3A8090"),
            page.replace("localhost%3A34321", "localhost"),
            "https://accounts.google.com/o/oauth2/auth?redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback",
            "https://example.com/",
            "",
        ).forEach { assertNull(it, SignInReturn.pagePort(it)) }
    }

    @Test
    fun `nothing but a return to this phone's localhost goes to the bridge`() {
        listOf(
            "https://localhost:1455/auth/callback?code=1", // https: no agent's local sign-in server
            "http://example.com:1455/auth/callback?code=1",
            "http://localhost.example.com:1455/",
            "http://localhost/auth/callback", // no port
            "http://localhost:80/auth/callback",
            "http://localhost:8090/pocketide/health", // the bridge itself
            "http://localhost:70000/",
            "intent://signin#Intent;end",
            "not a link",
            "",
        ).forEach { assertNull(it, SignInReturn.address(it, "a@b.c")) }
    }
}
