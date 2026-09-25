package com.pocketide.sync

import com.pocketide.rooms.RoomFiles
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel

/**
 * Opens a room file to upload it the way the rooms module reads room files ([RoomFiles]): from
 * the room's own folder, one folder at a time, never through a link. A program in the room that
 * swaps the file, or a folder above it, for a link while the phone reads cannot put any other
 * file (another room's sign-in, the app's own files) into the vault.
 *
 * [file] is [relative] under the room folder it was found in (its home, or its work folder for
 * media); what is opened is found again from that folder, not by [file]'s path.
 */
internal fun openInRoom(file: File, relative: String): SeekableByteChannel {
    val channel = try {
        val room = generateSequence(file) { it.parentFile }.elementAtOrNull(RoomFiles.components(relative).size)
        room?.let { RoomFiles(it, guardSecrets = true).open(relative) }
    } catch (refused: IllegalArgumentException) {
        null
    }
    return channel ?: throw IOException("$relative is not a plain file in its room any more.")
}

internal fun Candidate.openInRoom(): SeekableByteChannel = openInRoom(file, path)

internal fun Candidate.readInRoom(): InputStream = Channels.newInputStream(openInRoom())

internal fun RoomFile.readInRoom(): InputStream = Channels.newInputStream(openInRoom(file, path))
