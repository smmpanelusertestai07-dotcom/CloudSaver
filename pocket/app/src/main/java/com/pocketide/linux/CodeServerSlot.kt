package com.pocketide.linux

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * code-server lives in /opt/code-server-<version>, and /opt/code-server is a relative link to
 * the one in use. A new version is unpacked beside the current one, checked, and switched to
 * with one rename, so there is never a moment without a working code-server, and switching
 * back is the same rename.
 */
internal class CodeServerSlot(
    private val rootfs: File,
    private val extractor: TarGzExtractor,
    private val runner: GuestRunner,
) {
    private fun opt(): Path =
        GuestRoot(rootfs).directory("/opt", create = true) ?: throw IOException("/opt inside Linux is not a folder")

    fun installed(): Boolean {
        val binary = GuestRoot(rootfs).existing("$LINK/bin/code-server") ?: return false
        return GuestRoot.attributesOf(binary)?.isRegularFile == true
    }

    /** True when /opt/code-server-<version> was unpacked completely (it only appears by rename). */
    fun unpacked(version: String): Boolean = Files.isDirectory(opt().resolve(folder(version)))

    /** Unpacks the release archive into /opt/code-server-<version>, whole or not at all. */
    suspend fun unpack(version: String, archive: File, onProgress: (Float) -> Unit) {
        val target = opt().resolve(folder(version))
        val staging = opt().resolve(folder(version) + ".partial")
        Trees.delete(staging)
        // The release keeps everything under one top folder, code-server-<version>-linux-arm64.
        extractor.extract(archive, staging.toFile(), stripComponents = 1, onProgress = onProgress)
        Trees.delete(target)
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
    }

    /** True when the code-server under [guestFolder] starts and reports [version]. */
    suspend fun reports(guestFolder: String, version: String): Boolean {
        var first: String? = null
        val code = try {
            withTimeout(CHECK_TIMEOUT_MS) {
                runner.run(rootfs, listOf("$guestFolder/bin/code-server", "--version")) { line ->
                    if (first == null && line.isNotBlank()) first = line.trim()
                }
            }
        } catch (tooSlow: TimeoutCancellationException) {
            return false
        }
        return code == 0 && first?.substringBefore(' ') == version
    }

    /** Points /opt/code-server at [version]; returns the link's previous target, if there was one. */
    fun switchTo(version: String): String? = relink(folder(version))

    fun switchBack(previousTarget: String) {
        relink(previousTarget)
    }

    /** The version the link points at now. */
    fun current(): String? = runCatching { Files.readSymbolicLink(opt().resolve(NAME)).toString() }.getOrNull()
        ?.removePrefix("$NAME-")

    fun delete(version: String) = Trees.delete(opt().resolve(folder(version)))

    /** Removes every other version, and anything an interrupted unpack left behind. */
    fun prune(keep: String) {
        opt().toFile().listFiles { entry -> entry.name.startsWith("$NAME-") && entry.name != folder(keep) }
            ?.forEach { Trees.delete(it.toPath()) }
    }

    private fun relink(target: String): String? {
        val opt = opt()
        val link = opt.resolve(NAME)
        val attributes = GuestRoot.attributesOf(link)
        val previous = if (attributes?.isSymbolicLink == true) Files.readSymbolicLink(link).toString() else null
        // Anything that is not a link (a folder left by hand) gives way to the link.
        if (attributes != null && !attributes.isSymbolicLink) Trees.delete(link)
        val temporary = opt.resolve(".$NAME.link-${System.nanoTime()}")
        Files.createSymbolicLink(temporary, Paths.get(target))
        try {
            Files.move(temporary, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return previous
    }

    companion object {
        private const val NAME = "code-server"
        const val LINK = "/opt/code-server"
        private const val CHECK_TIMEOUT_MS = 120_000L

        fun folder(version: String) = "$NAME-$version"
    }
}
