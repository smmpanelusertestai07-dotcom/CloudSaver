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
        val ports = Agent.entries.map(CloudShell::port)
        assertEquals("ports are each agent's own", ports.size, ports.distinct().size)
        // PocketIDE 7's sign-in bridge (port 8090, for agents opened in Chrome) is gone, and the script removes it.
        assertFalse(text.contains("BRIDGE_PORT"))
        assertTrue(text.contains("rm -f \"\$BASE/bridge.py\" \"\$BASE/bridge.log\""))
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
    fun `one thing at a time, and PocketIDE's keys reach the layout extension's commands`() {
        val text = script.readText()
        val manifest = text.substringAfter("cat >\"\$BASE/layout/package.json\" <<'JSON'\n").substringBefore("\nJSON\n")
        val bindings = Regex("""\{\s*"command": "(pocketide\.[a-z]+)",\s*"key": "([a-z0-9+]+)"\s*}""").findAll(manifest)
            .associate { it.groupValues[2] to it.groupValues[1] }
        val page = listOf("src/main/assets", "app/src/main/assets").map { File(it, "workspace/pagescript.js") }.first { it.isFile }.readText()
        val keys = Regex("""(\w+): \['(F\d+)'(?:, '(ctrl)')?]""").findAll(page.substringAfter("const KEYS = {").substringBefore("};")).toList()
        val commands = listOf("back", "agent", "terminal", "settings", "commands", "vsix", "tools", "files")
        assertEquals("the page's commands", commands, keys.map { it.groupValues[1] })
        keys.forEach { key ->
            val chord = listOfNotNull(key.groupValues[3].ifBlank { null }, key.groupValues[2].lowercase()).joinToString("+")
            assertEquals("${key.groupValues[1]} presses $chord", "pocketide.${key.groupValues[1]}", bindings[chord])
        }
        val code = text.substringAfter("cat >\"\$BASE/layout/extension.js\" <<'JS'\n").substringBefore("\nJS\n")
        bindings.values.toSet().forEach { assertTrue(it, code.contains("registerCommand('$it'")) }
        // Editors and terminals open in the main editor area, which the extension shows full screen;
        // no settings in a floating window wider than the phone.
        assertTrue(text.contains("\"workbench.editor.useModal\": \"off\""))
        assertTrue(text.contains("\"terminal.integrated.defaultLocation\": \"editor\""))
        // PocketIDE's app decides where each link opens, so VS Code does not ask first as well.
        assertTrue(text.contains("--link-protection-trusted-domains '*'"))
        // A VS Code that was running keeps its old layout until it starts again: the set-up restarts them.
        assertTrue(text.contains("\"\$BIN/pocketide\" restart"))
    }

    @Test
    fun `the script keeps to the home folder and to Google's rules`() {
        val text = script.readText()
        // The browser's libraries come from Cloud Shell's own package lists, and only there; they
        // last one session (Cloud Shell's system goes back to Google's image each time it starts).
        val libraries = text.substringAfter("browser_libraries() {").substringBefore("\n}\n")
        val rest = text.replace(libraries, "")
        Regex("\\bsudo\\b[^\\n]*").findAll(libraries).forEach { assertTrue(it.value, it.value.startsWith("sudo -n apt-get ")) }
        // Otherwise only root's start-up hook uses sudo, and only to drop to the owner's own account.
        Regex("\\bsudo\\b[^\\n]*").findAll(rest).map { it.value }.forEach { assertTrue(it, it.startsWith("sudo -u ")) }
        listOf("apt-get", "apt install").forEach { assertFalse("the script uses $it outside the browser's libraries", rest.contains(it)) }
        listOf("apt install", "while true", "sleep infinity", "keepalive", "keep-alive", "xmrig", "nmap", "masscan", "0.0.0.0")
            .forEach { assertFalse("the script uses $it", text.contains(it)) }
        assertFalse("nothing downloaded runs unchecked", Regex("(curl|wget)[^\\n|]*\\|\\s*(ba)?sh").containsMatchIn(text))
        assertTrue("VS Code listens only inside Cloud Shell", text.contains("--bind-addr \"127.0.0.1:"))
    }

    @Test
    fun `each agent's VS Code starts only when PocketIDE opens it`() {
        val text = script.readText()
        val launcher = text.substringAfter("cat >\"\$BIN/pocketide\" <<'LAUNCHER'\n").substringBefore("\nLAUNCHER\n")
        val cases = launcher.substringAfter("case \"\${1:-}\" in\n")
        fun branch(name: String) = cases.substringAfter("\n$name)").substringBefore(";;")
        assertFalse("Cloud Shell's start starts no VS Code", Regex("\\bstart\\b").containsMatchIn(branch("boot")))
        assertEquals("a new terminal only puts the command lines in place", "links", branch("--quiet").trim())
        assertTrue("one agent at a time, by its name", branch("start").contains("port_of \"\$key\""))
        assertTrue(branch("stop").contains("stop \"\$@\""))
        assertTrue("Antigravity's backend stops with its VS Code", launcher.contains("pkill -u \"\$(id -u)\" -f \"agy --hub --hub-port=\$AGY_PORT \""))
        Agent.entries.forEach { agent -> assertTrue(launcher.contains("AGENTS=\"") && text.contains("${CloudShell.key(agent)}:${CloudShell.port(agent)}:")) }
    }

    @Test
    fun `the browser listens only inside Cloud Shell, comes from Google, and stops when unused`() {
        val text = script.readText()
        assertTrue(text.contains("--remote-debugging-address=127.0.0.1"))
        assertTrue(text.contains("\"--remote-debugging-port=\$CDP_PORT\""))
        assertTrue(text.contains("CDP_PORT=${CloudShell.BROWSER_DEVTOOLS_PORT}"))
        assertTrue(text.contains("VIEW_PORT=${CloudShell.BROWSER_PORT}"))
        val relay = text.substringAfter("cat >\"\$BASE/browser/relay.py\" <<'RELAY'\n").substringBefore("\nRELAY\n")
        assertTrue("its view listens only inside Cloud Shell", relay.contains("Server((\"127.0.0.1\", VIEW_PORT), View)"))
        assertTrue("it stops when no one used it for a while", relay.contains("IDLE_MINUTES = 20"))
        assertTrue("the owner's input is checked", relay.contains("if urllib.parse.urlparse(url).scheme in (\"http\", \"https\")"))
        assertTrue("Chrome comes from Google's own address", text.contains("startswith(\"https://storage.googleapis.com/chrome-for-testing-public/\")"))
        assertTrue("the agents are told how to use it, and to stop it", text.contains("pocketide browser stop"))
        assertTrue("it needs memory: the launcher says so first", text.contains("return 3"))
    }

    @Test
    fun `each agent is told Cloud Shell's rules, in its own instructions file`() {
        val text = script.readText()
        listOf("~/.claude/CLAUDE.md", "~/.codex/AGENTS.md", "~/.gemini/GEMINI.md").forEach { assertTrue(it, text.contains("\"$it\"")) }
        listOf("mine cryptocurrency", "scan networks", "expose a port to the internet", "keep Cloud Shell running on purpose")
            .forEach { assertTrue(it, text.contains(it)) }
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
    fun `Google's own page opens with the picked account`() {
        val account = "dev+pocket@example.com"
        assertEquals("https://shell.cloud.google.com/?show=terminal&authuser=dev%2Bpocket%40example.com", CloudShell.googlePage(account))
        assertEquals("no account, no authuser", "https://shell.cloud.google.com/?show=terminal", CloudShell.googlePage(""))
    }

    @Test
    fun `Antigravity's sign-in page names the phone port its return comes to`() {
        val page = "https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=1.apps.googleusercontent.com" +
            "&redirect_uri=http%3A%2F%2Flocalhost%3A34321%2Fauth%2Fcallback&response_type=code&state=s"
        assertEquals(34321, SignInReturn.pagePort(page))
        listOf(
            page.replace("https://", "http://"),
            page.replace("localhost%3A34321", "example.com%3A34321"),
            page.replace("localhost%3A34321", "localhost%3A80"),
            page.replace("localhost%3A34321", "localhost"),
            "https://accounts.google.com/o/oauth2/auth?redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback",
            "https://example.com/",
            "",
        ).forEach { assertNull(it, SignInReturn.pagePort(it)) }
    }
}
