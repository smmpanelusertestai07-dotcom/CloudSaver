package com.pocketide.agents

import com.pocketide.core.AppJson
import com.pocketide.core.SemVer
import com.pocketide.ide.AgentScreen
import com.pocketide.linux.Arch
import com.pocketide.linux.Computer
import com.pocketide.linux.GuestRoot
import com.pocketide.linux.LinuxCommand
import com.pocketide.linux.LinuxDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** An extension installed in the phone's code-server, read from its own package.json. */
data class InstalledExtension(
    /** Lower case, "publisher.name". */
    val id: String,
    val version: String,
    val displayName: String,
    /** Its screens (webview views), which make it an agent. */
    val views: List<String>,
) {
    val official: Agent? get() = Agent.of(id)
    val isAgent: Boolean get() = official != null || views.isNotEmpty()

    /** How the companion shows it: an official agent by its own command, any other by its first screen. */
    fun screen(): AgentScreen = official?.let(AgentScreen::of) ?: AgentScreen(id, emptyList(), views.take(1))
}

/** What one install or update did, for the screens. */
sealed interface InstallStep {
    data class Downloading(val name: String, val done: Long, val total: Long) : InstallStep
    data class Installing(val name: String) : InstallStep
}

/**
 * The agents (and any other extension) in the phone's code-server: listed from the extensions
 * folder, installed and updated from Open VSX only, and only in a form that passed every check:
 *
 * - its publisher is verified by Open VSX, it is not a look-alike of an official agent, and it
 *   is not Microsoft's (Microsoft's extensions may be used only in Microsoft's own products);
 * - a release, never a pre-release, built for this phone (linux-arm64) or for every platform,
 *   whose `engines.vscode` range accepts this code-server;
 * - the package matches the SHA-256 Open VSX publishes and Open VSX's own signature.
 *
 * code-server's own installer puts it in place, so the extension looks exactly as it would on a
 * computer.
 */
class AgentStore internal constructor(
    private val computer: Computer,
    private val dirs: LinuxDirs,
    private val vsx: OpenVsx,
    private val download: VerifiedDownload,
) {
    private val oneAtATime = Mutex()
    private val mutableInstalled = MutableStateFlow<List<InstalledExtension>>(emptyList())
    val installed: StateFlow<List<InstalledExtension>> = mutableInstalled.asStateFlow()

    private val mutableActivity = MutableStateFlow<InstallStep?>(null)

    /** The install or update under way, for every screen that shows progress; null when none is. */
    val activity: StateFlow<InstallStep?> = mutableActivity.asStateFlow()

    private val mutableProblem = MutableStateFlow<String?>(null)

    /** Why the last install did not finish, in the owner's words; null after one that did. */
    val problem: StateFlow<String?> = mutableProblem.asStateFlow()

    private val extensionsDir get() = File(dirs.home, EXTENSIONS)

    /** Reads the extensions folder again. */
    suspend fun refresh(): List<InstalledExtension> = withContext(Dispatchers.IO) {
        readInstalled().also { mutableInstalled.value = it }
    }

    /** The agents the companion should know: every installed extension with a screen. */
    suspend fun screens(): List<AgentScreen> = refresh().filter { it.isAgent }.map { it.screen() }

    suspend fun search(query: String): List<SearchEntry> = withContext(Dispatchers.IO) {
        vsx.search(query, offset = 0, size = SEARCH_SIZE).extensions.filterNot { it.deprecated }
    }

    /** Why [namespace].[name] may not be installed here, or null when it may. */
    fun refusal(namespace: String, name: String, verified: Boolean): String? = when {
        namespace.equals(MICROSOFT, ignoreCase = true) || namespace.equals(MS_PREFIX, ignoreCase = true) ->
            "Microsoft allows its extensions only in Microsoft's own products."
        !verified -> "Open VSX has not verified this publisher, so PocketIDE does not install it."
        else -> Lookalikes.problem(namespace, name)
    }

    /** Installs the official agents that are missing. */
    suspend fun installOfficial(onStep: (InstallStep) -> Unit = {}) {
        val have = refresh().map { it.id }.toSet()
        for (agent in Agent.entries) {
            if (agent.extensionId !in have) install(agent.publisher, agent.extensionName, onStep)
        }
    }

    /**
     * Installs the newest release of [namespace].[name] that passes every check; replaces an
     * older version. Returns what is installed afterwards.
     */
    suspend fun install(namespace: String, name: String, onStep: (InstallStep) -> Unit = {}): InstalledExtension =
        oneAtATime.withLock {
            val step = { value: InstallStep ->
                mutableActivity.value = value
                onStep(value)
            }
            try {
                step(InstallStep.Downloading("$namespace.$name", 0, -1))
                installLocked(namespace, name, step).also { mutableProblem.value = null }
            } catch (failure: IOException) {
                mutableProblem.value = failure.message
                throw failure
            } finally {
                mutableActivity.value = null
            }
        }

    private suspend fun installLocked(namespace: String, name: String, step: (InstallStep) -> Unit): InstalledExtension {
        val release = newest(namespace, name)
        val label = release.details.displayName ?: "$namespace.$name"
        val vsix = File(dirs.home, "$STAGING/${release.details.id}-${release.version}.vsix")
        try {
            val sha256 = vsx.sha256(release.details.files["sha256"])
            val expected = Expected(sha256 = sha256, signature = vsx.signature(release.details.files))
            download.fetch(release.url, vsix, expected) { done, total -> step(InstallStep.Downloading(label, done, total)) }
            step(InstallStep.Installing(label))
            codeServer("--install-extension", "${LinuxDirs.GUEST_HOME}/$STAGING/${vsix.name}", "--force")
        } finally {
            withContext(Dispatchers.IO) { Files.deleteIfExists(vsix.toPath()) }
        }
        return refresh().firstOrNull { it.id == release.details.id }
            ?: throw IOException("$label was installed, but code-server does not list it")
    }

    /** Installs a newer release of each installed extension from Open VSX; returns the names updated. */
    suspend fun updateAll(onStep: (InstallStep) -> Unit = {}): List<String> {
        val updated = mutableListOf<String>()
        for (extension in refresh()) {
            if (extension.id == COMPANION) continue
            val (namespace, name) = extension.id.split('.', limit = 2).takeIf { it.size == 2 } ?: continue
            val newest = runCatching { newest(namespace, name) }.getOrNull() ?: continue
            val have = SemVer.parse(extension.version) ?: continue
            val offered = SemVer.parse(newest.version) ?: continue
            if (offered > have && runCatching { install(namespace, name, onStep) }.isSuccess) updated += extension.displayName
        }
        return updated
    }

    /** Removes [id] with code-server's own command. */
    suspend fun remove(id: String) = oneAtATime.withLock {
        codeServer("--uninstall-extension", id)
        refresh()
    }

    /** The newest release this computer can run, from its own verified publisher. */
    private suspend fun newest(namespace: String, name: String): ExtensionRelease {
        val overview = vsx.extension(namespace, name) ?: throw IOException("$namespace.$name is not on Open VSX.")
        refusal(overview.namespace, overview.name, overview.verified)?.let { throw PackageRejected(it) }
        val arch = computer.arch ?: throw PackageRejected("This phone's processor is not supported.")
        val target = listOf(arch.openVsx, ExtensionVersion.UNIVERSAL).firstOrNull { it in overview.downloads }
            ?: throw PackageRejected("${overview.displayName ?: name} has no build for this phone (${arch.openVsx}).")
        val vscode = vscodeVersion() ?: throw IOException("code-server's version could not be read. Repair the computer.")
        val fits = { version: ExtensionVersion -> acceptable(version, overview.namespace, target, vscode) }
        val best = vsx.allVersions(overview.namespace, overview.name, target, enough = fits)
            .filter(fits)
            .maxWithOrNull(compareBy { SemVer.parse(it.version) })
            ?: throw PackageRejected("No release of ${overview.displayName ?: name} works with this code-server (VS Code $vscode).")
        val details = vsx.version(overview.namespace, overview.name, target, best.version)
            ?.takeIf(fits)
            ?: throw PackageRejected("${overview.displayName ?: name} ${best.version} did not pass PocketIDE's checks.")
        val link = details.downloads[target] ?: details.files["download"]
        val url = vsx.fileUrl(link) ?: throw PackageRejected("The download of ${overview.displayName ?: name} is not on Open VSX.")
        return ExtensionRelease(details, target, url)
    }

    private fun acceptable(version: ExtensionVersion, namespace: String, target: String, vscode: SemVer): Boolean {
        if (!SAFE_VERSION.matches(version.version)) return false
        val semver = SemVer.parse(version.version) ?: return false
        val engine = version.vscodeEngine?.let(EngineRange::parse) ?: return false
        return version.namespace.equals(namespace, ignoreCase = true) &&
            version.verified &&
            version.downloadable &&
            !version.preRelease &&
            !semver.isPreRelease &&
            version.targetPlatform == target &&
            (version.reviewStatus == null || version.reviewStatus == "published") &&
            engine.accepts(vscode)
    }

    /** The VS Code version code-server is built on, from its product.json. */
    private fun vscodeVersion(): SemVer? {
        val text = GuestRoot(dirs.rootfs).readText(PRODUCT_JSON, limit = MAX_JSON) ?: return null
        val product = runCatching { AppJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return product["version"]?.jsonPrimitive?.contentOrNull?.let(SemVer::parse)
    }

    private suspend fun codeServer(vararg args: String) {
        val said = ArrayDeque<String>()
        val code = computer.run(LinuxCommand(listOf(CODE_SERVER, "--user-data-dir", USER_DATA, "--extensions-dir", GUEST_EXTENSIONS) + args)) { line ->
            if (line.isNotBlank()) said.addLast(line)
            if (said.size > KEPT_LINES) said.removeFirst()
        }
        if (code != 0) throw IOException("code-server: ${said.lastOrNull()?.take(MAX_SAID) ?: "exit code $code"}")
    }

    private fun readInstalled(): List<InstalledExtension> {
        val folders = extensionsDir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }.orEmpty()
        return folders.mapNotNull { folder -> read(File(folder, "package.json")) }
            .groupBy { it.id }
            .map { (_, versions) -> versions.maxWith(compareBy { SemVer.parse(it.version) }) }
            .sortedBy { it.displayName.lowercase() }
    }

    private fun read(manifest: File): InstalledExtension? {
        if (!manifest.isFile || manifest.length() > MAX_JSON) return null
        val json = runCatching { AppJson.parseToJsonElement(manifest.readText()) as? JsonObject }.getOrNull() ?: return null
        val publisher = json.string("publisher") ?: return null
        val name = json.string("name") ?: return null
        val version = json.string("version") ?: return null
        val display = json.string("displayName")?.takeIf { !it.startsWith("%") } ?: name
        val views = (json["contributes"] as? JsonObject)?.get("views") as? JsonObject
        val screens = views?.values.orEmpty()
            .flatMap { (it as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }
            .filter { it.string("type") == "webview" }
            .mapNotNull { it.string("id") }
        return InstalledExtension("$publisher.$name".lowercase(), version, display, screens)
    }

    private fun JsonObject.string(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private companion object {
        const val USER_DATA = "/root/.local/share/code-server"
        const val GUEST_EXTENSIONS = "$USER_DATA/extensions"
        const val EXTENSIONS = ".local/share/code-server/extensions"
        const val STAGING = ".cache/pocketide"
        const val CODE_SERVER = "/opt/code-server/bin/code-server"
        const val PRODUCT_JSON = "/opt/code-server/lib/vscode/product.json"
        const val COMPANION = "pocketide.companion"
        const val MICROSOFT = "ms-vscode"
        const val MS_PREFIX = "microsoft"
        const val SEARCH_SIZE = 40
        const val MAX_JSON = 4L * 1024 * 1024
        const val KEPT_LINES = 5
        const val MAX_SAID = 160

        /** A version is also a file name here. */
        val SAFE_VERSION = Regex("[0-9A-Za-z][0-9A-Za-z.+-]{0,63}")
    }
}

/** The version of an extension to install: checked, for one platform, from its own download link. */
internal data class ExtensionRelease(val details: ExtensionVersion, val target: String, val url: okhttp3.HttpUrl) {
    val version: String get() = details.version
}
