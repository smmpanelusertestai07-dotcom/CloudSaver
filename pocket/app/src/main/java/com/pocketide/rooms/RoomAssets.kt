package com.pocketide.rooms

import android.content.res.AssetManager

/** The files this module ships in the APK's assets (rooms/ and web/). */
internal interface RoomAssets {
    /** Files under [folder], recursively, as paths relative to [folder]. */
    fun files(folder: String): List<String>

    fun read(path: String): ByteArray
}

internal class AndroidRoomAssets(private val assets: AssetManager) : RoomAssets {
    override fun files(folder: String): List<String> {
        val found = mutableListOf<String>()
        fun walk(relative: String) {
            val path = if (relative.isEmpty()) folder else "$folder/$relative"
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) {
                if (relative.isNotEmpty()) found += relative
                return
            }
            children.sorted().forEach { walk(if (relative.isEmpty()) it else "$relative/$it") }
        }
        walk("")
        return found
    }

    override fun read(path: String): ByteArray = assets.open(path).use { it.readBytes() }
}
