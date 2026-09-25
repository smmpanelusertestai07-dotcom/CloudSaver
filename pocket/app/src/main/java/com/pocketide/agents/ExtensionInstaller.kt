package com.pocketide.agents

import com.pocketide.core.Redact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The version of an extension to install: checked, for one platform, from its own download link. */
internal data class ExtensionRelease(val details: ExtensionVersion, val target: String, val url: HttpUrl) {
    val version: String get() = details.version
}

/** A package on the phone and the SHA-256 it was checked against. */
internal data class Fetched(val file: File, val sha256: String)

/**
 * Picks, fetches and installs an agent's extension. The version is the newest one that is a
 * release (never a pre-release: Open VSX's "latest" for Codex is one), from the verified
 * namespace the agent belongs to, built for linux-arm64 (or universal), and whose engine range
 * accepts the computer's code-server. The package must match Open VSX's SHA-256 and, when the
 * registry signed it, its signature. It is installed by the room's own code-server into the
 * room's own folders.
 */
internal class ExtensionInstaller(
    private val env: AgentsEnv,
    private val vsx: OpenVsx,
    private val download: VerifiedDownload,
    private val installTimeoutMs: Long = INSTALL_TIMEOUT_MS,
) {

    /** The newest version of [extensionId] this computer can run, from [namespace] only. */
    suspend fun newest(extensionId: String, namespace: String, vscode: SemVer): ExtensionRelease {
        val (overview, target) = overview(extensionId, namespace)
        val best = vsx.allVersions(overview.namespace, overview.name, target)
            .filter { acceptable(it, namespace, target, vscode) }
            .maxWithOrNull(compareBy { SemVer.parse(it.version) })
            ?: throw PackageRejected("No version of $extensionId works with this computer's code-server (VS Code $vscode).")
        return release(extensionId, overview, target, best.version, namespace, vscode)
    }

    /** [version] of [extensionId], when it still passes every check (to put a version back after a failed update). */
    suspend fun exact(extensionId: String, namespace: String, vscode: SemVer, version: String): ExtensionRelease {
        val (overview, target) = overview(extensionId, namespace)
        return release(extensionId, overview, target, version, namespace, vscode)
    }

    /** Downloads [release] into [folder] as `<version>.vsix`, kept only when it matches what Open VSX published. */
    suspend fun fetch(release: ExtensionRelease, folder: File): Fetched {
        val sha256 = vsx.sha256(release.details.files["sha256"])
        val expected = Expected(sha256 = sha256, signature = vsx.signature(release.details.files))
        return Fetched(download.fetch(release.url, packageFile(folder, release.version), expected), sha256)
    }

    private suspend fun overview(extensionId: String, namespace: String): Pair<ExtensionVersion, String> {
        if (extensionId.lowercase() in NEVER) throw PackageRejected("PocketIDE never installs $extensionId.")
        val name = extensionId.substringAfter('.')
        val overview = vsx.extension(namespace, name) ?: throw IOException("$extensionId is no longer on Open VSX.")
        if (!overview.namespace.equals(namespace, ignoreCase = true)) throw PackageRejected("$extensionId is not from its own publisher.")
        val target = Discovery.TARGETS.firstOrNull { it in overview.downloads }
            ?: throw PackageRejected("$extensionId has no build for this phone (Linux on arm64).")
        return overview to target
    }

    /** The per-version answer is the one to trust: its checks again, and its own download link. */
    private suspend fun release(
        extensionId: String,
        overview: ExtensionVersion,
        target: String,
        version: String,
        namespace: String,
        vscode: SemVer,
    ): ExtensionRelease {
        val details = vsx.version(overview.namespace, overview.name, target, version)
            ?: throw IOException("Open VSX no longer has $extensionId $version.")
        if (!acceptable(details, namespace, target, vscode)) throw PackageRejected("$extensionId $version did not pass PocketIDE's checks.")
        val link = details.downloads[target] ?: details.files["download"]
        val url = vsx.fileUrl(link) ?: throw PackageRejected("The download of $extensionId is not on Open VSX.")
        return ExtensionRelease(details, target, url)
    }

    /** Installs [vsix] into [agentId]'s room with the room's own code-server; replaces any other version. */
    suspend fun install(agentId: String, extensionId: String, vsix: File) {
        val staged = stage(agentId, vsix)
        try {
            val lines = ArrayDeque<String>()
            val code = inRoom(agentId, RoomPaths.codeServer("--install-extension", "/tmp/${staged.name}", "--force")) { line ->
                if (line.isNotBlank()) lines.addLast(line)
                if (lines.size > KEPT_LINES) lines.removeFirst()
            }
            if (code != 0) throw IOException("code-server could not install $extensionId: ${said(lines)}")
        } finally {
            withContext(Dispatchers.IO) { Files.deleteIfExists(staged.toPath()) }
        }
    }

    /** Removes [extensionId] from the room (a first install that failed its test). */
    suspend fun uninstall(agentId: String, extensionId: String) {
        inRoom(agentId, RoomPaths.codeServer("--uninstall-extension", extensionId))
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

    /** The package, seen by the room as /tmp/<name>: a link when the storage allows one, else a copy. */
    private suspend fun stage(agentId: String, vsix: File): File = withContext(Dispatchers.IO) {
        val tmp = env.dirs.roomTmp(agentId)
        Files.createDirectories(tmp.toPath())
        val staged = File(tmp, "pocketide-install-${vsix.name}")
        Files.deleteIfExists(staged.toPath())
        try {
            Files.createLink(staged.toPath(), vsix.toPath())
        } catch (refused: IOException) {
            Files.copy(vsix.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (unsupported: UnsupportedOperationException) {
            Files.copy(vsix.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        staged
    }

    private suspend fun inRoom(agentId: String, argv: List<String>, onLine: (String) -> Unit = {}): Int = try {
        withTimeout(installTimeoutMs) { env.runInRoom(agentId, argv, onLine) }
    } catch (tooSlow: TimeoutCancellationException) {
        throw IOException("code-server took too long. Try again when the phone is less busy.")
    }

    /** What code-server said last, cleaned for a message: it comes from inside Linux. */
    private fun said(lines: Collection<String>): String =
        Redact.text(lines.lastOrNull().orEmpty()).map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(MAX_SAID)
            .ifEmpty { "no reason given" }

    companion object {
        /** The Antigravity extension downloads agy by itself, past the pin and the doctor (risk R32). */
        val NEVER = setOf("google.google-antigravity")

        /** A 240 MB package unpacks for minutes on a slow phone. */
        private const val INSTALL_TIMEOUT_MS = 20 * 60_000L
        private const val KEPT_LINES = 5
        private const val MAX_SAID = 160
        /** A version is also a file name here. */
        private val SAFE_VERSION = Regex("[0-9A-Za-z][0-9A-Za-z.+-]{0,63}")

        fun packageFile(folder: File, version: String) = File(folder, "$version.vsix")
    }
}
