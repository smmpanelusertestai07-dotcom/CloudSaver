package com.pocketide.ide

import com.pocketide.agents.Agent
import com.pocketide.core.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

class IdeFilesTest {
    private val companionDir: File by lazy {
        listOf(File("src/main/assets/companion"), File("app/src/main/assets/companion")).first { it.isDirectory }
    }

    private fun companionFiles(): Map<String, ByteArray> = companionDir.listFiles().orEmpty().associate { it.name to it.readBytes() }

    @Test
    fun `the phone layout hides VS Code's own parts and keeps its hands off updates and telemetry`() {
        val settings = AppJson.parseToJsonElement(IdeFiles.machineSettings(fontSize = 15)) as JsonObject
        mapOf(
            "workbench.activityBar.location" to "hidden",
            "workbench.statusBar.visible" to "false",
            "workbench.secondarySideBar.defaultVisibility" to "maximized",
            "chat.disableAIFeatures" to "true",
            "extensions.autoUpdate" to "false",
            "telemetry.telemetryLevel" to "off",
            "editor.fontSize" to "15",
        ).forEach { (key, value) -> assertEquals(key, value, settings[key]?.jsonPrimitive?.content) }
        val terminalEnv = settings["terminal.integrated.env.linux"] as JsonObject
        assertEquals("Terminal links open in the phone's browser", IdeFiles.BROWSER, terminalEnv["BROWSER"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the companion learns how each official agent opens`() {
        val list = AppJson.parseToJsonElement(IdeFiles.agentsList(Agent.entries.map(AgentScreen::of))) as JsonArray
        assertEquals(Agent.entries.map { it.extensionId }, list.map { (it as JsonObject)["id"]!!.jsonPrimitive.content })
        val antigravity = list.map { it as JsonObject }.single { it["id"]!!.jsonPrimitive.content == Agent.ANTIGRAVITY.extensionId }
        assertEquals(listOf("antigravity.panel"), (antigravity["views"] as JsonArray).map { it.jsonPrimitive.content })
    }

    @Test
    fun `the companion package is a vsix code-server can install`() {
        val files = companionFiles()
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(IdeFiles.companionPackage(files))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertTrue(entries.containsKey("extension.vsixmanifest"))
        assertTrue(entries.containsKey("[Content_Types].xml"))
        files.forEach { (name, bytes) -> assertTrue(name, entries["extension/$name"].contentEquals(bytes)) }
        val manifest = AppJson.parseToJsonElement(String(entries.getValue("extension/package.json"))) as JsonObject
        assertEquals("pocketide", manifest["publisher"]!!.jsonPrimitive.content)
        assertEquals(IdeFiles.COMPANION_VERSION, manifest["version"]!!.jsonPrimitive.content)
        assertEquals(IdeFiles.COMPANION_ID, "pocketide." + manifest["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sign-in sites are added once, and a product file that is not JSON is left alone`() {
        val product = """{"nameShort":"code-server","linkProtectionTrustedDomains":["https://open-vsx.org"]}"""
        val trusted = IdeFiles.trustSignInSites(product)!!
        val domains = ((AppJson.parseToJsonElement(trusted) as JsonObject)["linkProtectionTrustedDomains"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals("https://open-vsx.org", domains.first())
        assertTrue(domains.containsAll(IdeFiles.SIGN_IN_SITES))
        assertEquals(trusted, IdeFiles.trustSignInSites(trusted))
        assertNull(IdeFiles.trustSignInSites("not json"))
    }

    /** What the engine test runs code-server with: the exact files the app writes. */
    @Test
    fun `the files are written for the engine test`() {
        val out = File("build/ide-files").apply { mkdirs() }
        File(out, "machine-settings.json").writeText(IdeFiles.machineSettings(fontSize = 14))
        File(out, "agents.json").writeText(IdeFiles.agentsList(Agent.entries.map(AgentScreen::of)))
        File(out, "companion.vsix").writeBytes(IdeFiles.companionPackage(companionFiles()))
        File(out, "trusted-sites.txt").writeText(IdeFiles.SIGN_IN_SITES.joinToString("\n") + "\n")
        assertTrue(File(out, "companion.vsix").length() > 0)
    }
}
