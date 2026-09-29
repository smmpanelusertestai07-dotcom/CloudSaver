package com.pocketide.linux

import android.content.Context
import java.io.File

/**
 * Where the computer lives, all of it in the app's private storage: no other app, and nothing on
 * the phone's shared storage, can read it.
 *
 * | Host (under files/computer/) | Inside Linux | Reset             |
 * |------------------------------|--------------|-------------------|
 * | rootfs/                      | /            | deleted, rebuilt  |
 * | home/                        | /root        | kept              |
 *
 * The home holds everything that is the owner's: projects, each agent's sign-in, settings and
 * chats, and the installed extensions. Ubuntu itself can be rebuilt at any time from the pinned
 * downloads, so "Reset" never touches the owner's work.
 */
class LinuxDirs(val base: File) {
    val rootfs = File(base, "rootfs")
    val home = File(base, "home")

    /** Partial and finished downloads of the pinned archives. */
    val downloads = File(base, "downloads")

    /** How far set-up got ([SetupRecord]); outside the rootfs, so nothing inside Linux can change it. */
    val record = File(base, "computer.json")

    /** PRoot's own temporary files, and each command's private variables file. */
    val prootTmp = File(base, "proot-tmp")

    /** Stand-ins for the /proc files Android hides ([ProcStandIns]). */
    val procFakes = File(base, "proc")

    /** Links a program inside Linux asks the phone to open (xdg-open writes them, the app opens them). */
    val openRequests = File(home, OPEN_REQUESTS)

    /** Requests from the app to the companion extension inside code-server. */
    val companionRequests = File(home, COMPANION_REQUESTS)

    /** The app's own files inside the home: code-server's config and the forwarder's port. */
    val appFiles = File(home, APP_FILES)

    /** The owner's projects, one folder each. */
    val projects = File(home, PROJECTS)

    companion object {
        const val FOLDER = "computer"
        const val GUEST_HOME = "/root"
        const val APP_FILES = ".pocketide"
        const val OPEN_REQUESTS = "$APP_FILES/open"
        const val COMPANION_REQUESTS = "$APP_FILES/requests"
        const val PROJECTS = "projects"
        const val GUEST_PROJECTS = "$GUEST_HOME/$PROJECTS"

        fun of(context: Context) = LinuxDirs(File(context.filesDir, FOLDER))
    }
}
