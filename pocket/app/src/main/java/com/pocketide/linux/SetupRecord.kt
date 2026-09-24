package com.pocketide.linux

import com.pocketide.core.AppJson
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * How far the computer has been built, kept outside the rootfs so nothing inside Linux can
 * change what the app believes about it. Each step is written as soon as it is done, which is
 * what lets a set-up killed by a flat battery continue where it stopped.
 */
@Serializable
internal data class SetupRecord(
    /** Ubuntu version and SHA-256 of the base image the rootfs was unpacked from. */
    val ubuntu: String? = null,
    val base: String? = null,
    /** SHA-256 of the bootstrap.sh that last finished inside it. */
    val bootstrap: String? = null,
    /** The code-server at /opt/code-server, and the SHA-256 of the archive it came from. */
    val codeServer: String? = null,
    val codeServerSha256: String? = null,
    /** When set-up finished (UTC epoch ms); null while it is still under way. */
    val readyAt: Long? = null,
)

internal class RecordStore(private val file: File) {
    fun load(): SetupRecord =
        runCatching { AppJson.decodeFromString(SetupRecord.serializer(), file.readText()) }.getOrDefault(SetupRecord())

    /** Written in full and synced before it replaces the old record, so a kill leaves one or the other. */
    fun save(record: SetupRecord) {
        file.parentFile?.let { Files.createDirectories(it.toPath()) }
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(AppJson.encodeToString(SetupRecord.serializer(), record).toByteArray())
            output.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun clear() {
        Files.deleteIfExists(file.toPath())
    }
}
