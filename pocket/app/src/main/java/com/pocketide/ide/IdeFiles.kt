package com.pocketide.ide

import com.pocketide.agents.Agent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** An agent as the companion extension needs it: how its screen opens. */
data class AgentScreen(val id: String, val open: List<String>, val views: List<String>) {
    companion object {
        fun of(agent: Agent) = AgentScreen(agent.extensionId, listOfNotNull(agent.openCommand), agent.views)
    }
}

/**
 * The files PocketIDE writes for code-server: its settings, the agents list the companion reads,
 * the companion itself, and the sign-in sites code-server may open without asking.
 */
object IdeFiles {
    const val USER_DATA = "/root/.local/share/code-server"
    const val EXTENSIONS = "$USER_DATA/extensions"

    /**
     * Machine settings rank above the owner's own (User) settings in code-server, so writing this
     * file at every start keeps the phone layout whatever was changed in between.
     */
    const val MACHINE_SETTINGS = "$USER_DATA/Machine/settings.json"
    const val AGENTS = "/root/.pocketide/agents.json"
    const val COMPANION_ID = "pocketide.companion"
    const val COMPANION_VERSION = "5.0.0"
    const val BROWSER = "/opt/pocketide/bin/xdg-open"

    private val pretty = Json { prettyPrint = true }

    /**
     * code-server's environment: the owner's [keys], links opened in the phone's browser, and VS
     * Code's own extension gallery off. PocketIDE installs and updates the agents itself, each
     * package checked against its signature; the gallery would update them behind it, on mobile data
     * too. No setting stops that here: extensions.autoUpdate is an application setting, which VS
     * Code takes only from the owner's own settings, never from the machine settings below.
     */
    fun serverEnvironment(keys: Map<String, String>): Map<String, String> = keys + mapOf("BROWSER" to BROWSER) + NO_GALLERY_ENVIRONMENT

    /** VS Code's own extension gallery off, for the server and for code-server's install command. */
    val NO_GALLERY_ENVIRONMENT: Map<String, String> = mapOf("EXTENSIONS_GALLERY" to NO_GALLERY)

    /**
     * VS Code settings for a phone screen with the agents in front: no status, menu or activity
     * bar, the agents' side bar full screen, Enter for a new line (Send sends), no built-in AI chat
     * next to the official agents, no telemetry, and no updates behind PocketIDE's back (it checks
     * every package itself). Links from the terminal open in the phone's browser.
     */
    fun machineSettings(fontSize: Int): String {
        val settings = LinkedHashMap<String, JsonElement>()
        settings.putAll(PHONE)
        settings["editor.fontSize"] = JsonPrimitive(fontSize)
        settings["terminal.integrated.fontSize"] = JsonPrimitive(fontSize)
        settings["terminal.integrated.env.linux"] = buildJsonObject { put("BROWSER", BROWSER) }
        // A phone's WebView draws the terminal reliably without the GPU renderer.
        settings["terminal.integrated.gpuAcceleration"] = JsonPrimitive("off")
        settings.putAll(AGENTS_SETTINGS)
        return pretty.encodeToString(JsonObject.serializer(), JsonObject(settings)) + "\n"
    }

    /** The list the companion reads to show an agent and to move its view where a phone shows it. */
    fun agentsList(agents: List<AgentScreen>): String = pretty.encodeToString(
        JsonArray.serializer(),
        buildJsonArray {
            agents.forEach { agent ->
                add(
                    buildJsonObject {
                        put("id", agent.id)
                        put("open", JsonArray(agent.open.map(::JsonPrimitive)))
                        put("views", JsonArray(agent.views.map(::JsonPrimitive)))
                    },
                )
            }
        },
    ) + "\n"

    /**
     * The companion as a .vsix, the package code-server installs with --install-extension, built
     * from the app's assets/companion folder ([files]: path inside it to bytes).
     */
    fun companionPackage(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            add("[Content_Types].xml", CONTENT_TYPES.toByteArray())
            add("extension.vsixmanifest", VSIX_MANIFEST.toByteArray())
            files.toSortedMap().forEach { (name, bytes) -> add("extension/$name", bytes) }
        }
        return out.toByteArray()
    }

    /**
     * code-server's product.json with the agents' sign-in sites added to the ones it opens without
     * asking "Do you want to open the external website?" first. Null when the text is not a JSON
     * object (left as it is).
     */
    fun trustSignInSites(productJson: String): String? {
        val product = runCatching { Json.parseToJsonElement(productJson) as? JsonObject }.getOrNull() ?: return null
        val current = (product[TRUSTED] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        val merged = (current + SIGN_IN_SITES).distinct()
        if (merged == current) return productJson
        return pretty.encodeToString(JsonObject.serializer(), JsonObject(product + (TRUSTED to JsonArray(merged.map(::JsonPrimitive))))) + "\n"
    }

    private const val TRUSTED = "linkProtectionTrustedDomains"

    /** A gallery with no address: code-server then has none. */
    private const val NO_GALLERY = "{}"

    /** Where the agents send the owner to sign in, and GitHub for its own sign-in. */
    val SIGN_IN_SITES = listOf(
        "https://claude.ai", "https://claude.com", "https://*.claude.com", "https://*.anthropic.com",
        "https://auth.openai.com", "https://chatgpt.com", "https://*.openai.com",
        "https://accounts.google.com", "https://antigravity.google", "https://*.antigravity.google",
        "https://github.com",
    )

    private val PHONE: Map<String, JsonElement> = linkedMapOf(
        "workbench.startupEditor" to JsonPrimitive("none"),
        "workbench.secondarySideBar.defaultVisibility" to JsonPrimitive("maximized"),
        "workbench.secondarySideBar.showLabels" to JsonPrimitive(true),
        "workbench.activityBar.location" to JsonPrimitive("hidden"),
        "workbench.statusBar.visible" to JsonPrimitive(false),
        "window.menuBarVisibility" to JsonPrimitive("hidden"),
        "window.commandCenter" to JsonPrimitive(false),
        "window.confirmBeforeClose" to JsonPrimitive("never"),
        "workbench.layoutControl.enabled" to JsonPrimitive(false),
        "workbench.editor.showTabs" to JsonPrimitive("single"),
        "workbench.panel.opensMaximized" to JsonPrimitive("always"),
        "workbench.tips.enabled" to JsonPrimitive(false),
        "workbench.welcomePage.walkthroughs.openOnInstall" to JsonPrimitive(false),
        "workbench.enableExperiments" to JsonPrimitive(false),
        "workbench.reduceMotion" to JsonPrimitive("on"),
        "workbench.sash.size" to JsonPrimitive(20),
        "window.autoDetectColorScheme" to JsonPrimitive(true),
        "workbench.preferredDarkColorTheme" to JsonPrimitive("Default Dark Modern"),
        "workbench.preferredLightColorTheme" to JsonPrimitive("Default Light Modern"),
        "breadcrumbs.enabled" to JsonPrimitive(false),
        "editor.minimap.enabled" to JsonPrimitive(false),
        "editor.wordWrap" to JsonPrimitive("on"),
        "editor.stickyScroll.enabled" to JsonPrimitive(false),
        "editor.dragAndDrop" to JsonPrimitive(false),
        "editor.hover.delay" to JsonPrimitive(1500),
        "diffEditor.renderSideBySide" to JsonPrimitive(false),
        "files.autoSave" to JsonPrimitive("afterDelay"),
        "extensions.ignoreRecommendations" to JsonPrimitive(true),
        "update.mode" to JsonPrimitive("none"),
        "chat.disableAIFeatures" to JsonPrimitive(true),
        "telemetry.telemetryLevel" to JsonPrimitive("off"),
        // A task file an agent wrote must not run by itself when the folder opens.
        "task.allowAutomaticTasks" to JsonPrimitive("off"),
    )

    private val AGENTS_SETTINGS: Map<String, JsonElement> = linkedMapOf(
        "claudeCode.preferredLocation" to JsonPrimitive("sidebar"),
        "claudeCode.useCtrlEnterToSend" to JsonPrimitive(true),
        "claudeCode.hideOnboarding" to JsonPrimitive(true),
        "chatgpt.openOnStartup" to JsonPrimitive(false),
        "chatgpt.composerEnterBehavior" to JsonPrimitive("cmdAlways"),
    )

    private val CONTENT_TYPES = """
        <?xml version="1.0" encoding="utf-8"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension=".json" ContentType="application/json"/><Default Extension=".js" ContentType="application/javascript"/><Default Extension=".svg" ContentType="image/svg+xml"/><Default Extension=".vsixmanifest" ContentType="text/xml"/></Types>
    """.trimIndent()

    private val VSIX_MANIFEST = """
        <?xml version="1.0" encoding="utf-8"?>
        <PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011" xmlns:d="http://schemas.microsoft.com/developer/vsx-schema-design/2011">
          <Metadata>
            <Identity Language="en-US" Id="companion" Version="$COMPANION_VERSION" Publisher="pocketide" />
            <DisplayName>PocketIDE Companion</DisplayName>
            <Description xml:space="preserve">Shows the agent you pick in PocketIDE full screen, and opens its sign-in terminal with the command typed.</Description>
            <Categories>Other</Categories>
            <Properties>
              <Property Id="Microsoft.VisualStudio.Code.Engine" Value="^1.94.0" />
              <Property Id="Microsoft.VisualStudio.Code.ExtensionKind" Value="workspace" />
            </Properties>
          </Metadata>
          <Installation><InstallationTarget Id="Microsoft.VisualStudio.Code" /></Installation>
          <Dependencies />
          <Assets><Asset Type="Microsoft.VisualStudio.Code.Manifest" Path="extension/package.json" Addressable="true" /></Assets>
        </PackageManifest>
    """.trimIndent()
}
