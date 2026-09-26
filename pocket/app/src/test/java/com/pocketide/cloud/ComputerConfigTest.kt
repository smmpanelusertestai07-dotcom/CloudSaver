package com.pocketide.cloud

import com.pocketide.core.AppJson
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ComputerConfigTest {
    private val files = ComputerConfig.files().associateBy { it.path }
    private val devcontainer = files.getValue(ComputerConfig.DEVCONTAINER).text
    private val script = files.getValue(ComputerConfig.SETUP)

    /** devcontainer.json is JSON with comments; its body after the comment lines is plain JSON. */
    private val config by lazy { AppJson.parseToJsonElement(devcontainer.lines().dropWhile { it.startsWith("//") }.joinToString("\n")).jsonObject }

    @Test
    fun `three files in PocketIDE's own folder, the script executable`() {
        assertEquals(setOf(ComputerConfig.DEVCONTAINER, ComputerConfig.SETTINGS, ComputerConfig.SETUP), files.keys)
        assertTrue(files.keys.all { it.startsWith(".devcontainer/pocketide/") })
        assertTrue(script.executable)
    }

    @Test
    fun `the computer uses GitHub's default image and the three official agents`() {
        assertEquals("mcr.microsoft.com/devcontainers/universal:2", config["image"]!!.jsonPrimitive.content)
        val extensions = config["customizations"]!!.jsonObject["vscode"]!!.jsonObject["extensions"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("anthropic.claude-code", "openai.chatgpt", "google.google-antigravity"), extensions)
    }

    @Test
    fun `set-up runs on create and on every start, never in an attach terminal`() {
        assertEquals("bash ${ComputerConfig.SETUP} create", config["postCreateCommand"]!!.jsonPrimitive.content)
        assertEquals("bash ${ComputerConfig.SETUP} start", config["postStartCommand"]!!.jsonPrimitive.content)
        assertNull(config["postAttachCommand"])
    }

    @Test
    fun `the computer has a desktop to watch, Claude's command-line tool, and room for a browser`() {
        assertEquals(
            setOf("ghcr.io/devcontainers/features/desktop-lite:1", "ghcr.io/anthropics/devcontainer-features/claude-code:1.0"),
            config["features"]!!.jsonObject.keys,
        )
        assertEquals(listOf(ComputerConfig.DESKTOP_PORT), config["forwardPorts"]!!.jsonArray.map { it.jsonPrimitive.int })
        val desktop = config["portsAttributes"]!!.jsonObject["${ComputerConfig.DESKTOP_PORT}"]!!.jsonObject
        assertEquals("Desktop", desktop["label"]!!.jsonPrimitive.content)
        assertEquals(listOf("--shm-size=1g"), config["runArgs"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `each agent gets the phone notes once, after the owner's own, and the browser tools`() {
        val home = Files.createTempDirectory("home").toFile().apply { deleteOnExit() }
        val own = File(home, ".claude/CLAUDE.md").apply {
            parentFile?.mkdirs()
            writeText("# Mine\nKeep this.\n")
        }
        val script = File(home, "setup.sh").apply { writeText(this@ComputerConfigTest.script.text) }
        repeat(2) { run(home, listOf("bash", script.path, "start"), home = home) }
        for (path in listOf(".claude/CLAUDE.md", ".codex/AGENTS.md", ".gemini/GEMINI.md")) {
            val text = File(home, path).readText()
            assertEquals(path, 1, Regex("<!-- PocketIDE: start -->").findAll(text).count())
            assertTrue(path, "menu > Desktop" in text && text.trimEnd().endsWith("<!-- PocketIDE: end -->"))
        }
        assertTrue(own.readText().startsWith("# Mine\nKeep this.\n<!-- PocketIDE: start -->"))
        val servers = AppJson.parseToJsonElement(File(home, ".gemini/config/mcp_config.json").readText()).jsonObject
        assertEquals(setOf("playwright", "chrome-devtools"), servers["mcpServers"]!!.jsonObject.keys)
    }

    @Test
    fun `settings put the agents in front and the built-in chat away`() {
        val settings = AppJson.parseToJsonElement(files.getValue(ComputerConfig.SETTINGS).text).jsonObject
        assertEquals("maximized", settings["workbench.secondarySideBar.defaultVisibility"]!!.jsonPrimitive.content)
        assertTrue(settings["chat.disableAIFeatures"]!!.jsonPrimitive.boolean)
        assertTrue(settings["claudeCode.useCtrlEnterToSend"]!!.jsonPrimitive.boolean)
        assertEquals("off", settings["task.allowAutomaticTasks"]!!.jsonPrimitive.content)
        assertEquals("the same settings in both files", settings, config["customizations"]!!.jsonObject["vscode"]!!.jsonObject["settings"])
    }

    @Test
    fun `each file names the set-up version, and older or missing ones are told apart`() {
        assertTrue(ComputerConfig.files().all { ComputerConfig.MARKER in it.text || it.path == ComputerConfig.SETTINGS })
        assertEquals(SetUpFiles.CURRENT, ComputerConfig.status(devcontainer))
        assertEquals(SetUpFiles.OUTDATED, ComputerConfig.status("// Made by PocketIDE (set-up version 0).\n{}"))
        assertEquals(SetUpFiles.MISSING, ComputerConfig.status(null))
        assertEquals(SetUpFiles.MISSING, ComputerConfig.status("{ \"name\": \"their own\" }"))
    }

    @Test
    fun `the script is valid bash, guards pushes and never fails the start`() {
        val file = Files.createTempFile("setup", ".sh").toFile().apply { deleteOnExit() }
        file.writeText(script.text)
        val check = ProcessBuilder("bash", "-n", file.path).redirectErrorStream(true).start()
        assertTrue(check.waitFor(20, TimeUnit.SECONDS))
        assertEquals(check.inputStream.bufferedReader().readText(), 0, check.exitValue())
        assertTrue(script.text.contains("git merge-base --is-ancestor"))
        assertTrue(script.text.trimEnd().endsWith("exit 0"))
        assertFalse(script.text.contains("curl"))
    }

    @Test
    fun `the push guard blocks a force push and lets a normal one through`() {
        val repo = Files.createTempDirectory("guard").toFile().apply { deleteOnExit() }
        fun git(vararg args: String) = run(repo, listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", *args))
        git("init", "-q", "-b", "main")
        File(repo, "a").writeText("1")
        git("add", "a")
        git("commit", "-qm", "one")
        val first = git("rev-parse", "HEAD").trim()
        File(repo, "a").writeText("2")
        git("commit", "-qam", "two")
        val second = git("rev-parse", "HEAD").trim()
        val script = File(repo, "setup.sh").apply { writeText(this@ComputerConfigTest.script.text) }
        // Its own HOME, so the settings step writes into the test's folder, not the build machine's.
        run(repo, listOf("bash", script.path, "start"), home = repo)
        val hook = File(git("rev-parse", "--git-path", "hooks/pre-push").trim().let { if (File(it).isAbsolute) it else "${repo.path}/$it" })
        assertTrue(hook.canExecute())

        val forward = runHook(repo, hook, "refs/heads/main $second refs/heads/main $first")
        assertEquals(0, forward)
        val rewrite = runHook(repo, hook, "refs/heads/main $first refs/heads/main $second")
        assertEquals(1, rewrite)
    }

    private fun run(dir: File, command: List<String>, home: File? = null): String {
        val builder = ProcessBuilder(command).directory(dir).redirectErrorStream(true)
        home?.let {
            builder.environment()["HOME"] = it.path
            // The system's tools only: no agent command-line tool of the build machine's own.
            builder.environment()["PATH"] = "/usr/bin:/bin"
        }
        val process = builder.start()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "timed out: $command" }
        return process.inputStream.bufferedReader().readText()
    }

    private fun runHook(dir: File, hook: File, line: String): Int {
        val process = ProcessBuilder("bash", hook.path, "origin", "https://example.invalid/repo.git").directory(dir).start()
        process.outputStream.bufferedWriter().use { it.write(line + "\n") }
        check(process.waitFor(30, TimeUnit.SECONDS))
        return process.exitValue()
    }
}
