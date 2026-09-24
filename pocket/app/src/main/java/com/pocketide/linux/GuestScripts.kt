package com.pocketide.linux

import android.content.res.AssetManager
import java.io.File

/** The scripts shipped in the app's assets/linux folder. */
internal interface LinuxAssets {
    fun names(): List<String>
    fun read(name: String): ByteArray
}

internal class AndroidLinuxAssets(private val assets: AssetManager) : LinuxAssets {
    override fun names(): List<String> = assets.list(FOLDER)?.sorted().orEmpty()
    override fun read(name: String): ByteArray = assets.open("$FOLDER/$name").use { it.readBytes() }

    private companion object {
        const val FOLDER = "linux"
    }
}

/** Runs a program inside a given Linux root (production: proot). */
internal fun interface GuestRunner {
    suspend fun run(root: File, argv: List<String>, onLine: (String) -> Unit): Int
}

/** How a script ended, and the last thing it said, for the message the owner sees. */
internal data class ScriptResult(val exitCode: Int, val lastWords: String?)

/**
 * The app's scripts inside Linux. They are copied into /opt/pocketide before every use, so an
 * app update replaces them, and run with their output read line by line.
 */
internal class GuestScripts(private val assets: LinuxAssets, private val runner: GuestRunner) {

    fun install(rootfs: File) {
        val guest = GuestRoot(rootfs)
        for (name in assets.names()) {
            val mode = if (name.endsWith(".sh")) FileModes.EXECUTABLE else FileModes.PLAIN
            guest.write("$FOLDER/$name", assets.read(name), mode)
        }
    }

    /** Identifies the bootstrap this app carries, so a changed one runs again after an update. */
    fun bootstrapVersion(): String = Downloader.sha256(assets.read(BOOTSTRAP))

    suspend fun run(rootfs: File, script: String, onLine: (GuestLine) -> Unit): ScriptResult {
        var lastWords: String? = null
        val code = runner.run(rootfs, listOf("/bin/bash", "$FOLDER/$script")) { raw ->
            val line = GuestLines.parse(raw)
            if (line is GuestLine.Text && line.text.isNotEmpty()) lastWords = line.text
            onLine(line)
        }
        return ScriptResult(code, lastWords)
    }

    companion object {
        const val FOLDER = "/opt/pocketide"
        const val BOOTSTRAP = "bootstrap.sh"
        const val UPDATE = "update.sh"
    }
}
