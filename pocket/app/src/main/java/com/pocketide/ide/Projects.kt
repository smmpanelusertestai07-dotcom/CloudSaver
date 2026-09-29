package com.pocketide.ide

import com.pocketide.linux.LinuxDirs
import java.io.File
import java.io.IOException

/**
 * The owner's projects: one folder each in ~/projects, inside the app's private storage. An agent
 * opens with the chosen project as its workspace; a project from GitHub is cloned into a new one
 * with git in the terminal.
 */
class Projects(private val dirs: LinuxDirs) {
    /** Project names, oldest name first. */
    fun list(): List<String> = dirs.projects.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
        .orEmpty().map { it.name }.sortedBy { it.lowercase() }

    /** Makes the project [name]; returns its name. */
    fun create(name: String): String {
        problem(name)?.let { throw IllegalArgumentException(it) }
        val folder = File(dirs.projects, name)
        if (folder.exists()) throw IOException("A project called $name is already there.")
        if (!folder.mkdirs()) throw IOException("The project folder could not be made.")
        return name
    }

    /** The folder inside Linux an agent opens for [name]; all projects when it is empty or gone. */
    fun guestFolder(name: String): String =
        if (name.isNotEmpty() && problem(name) == null && File(dirs.projects, name).isDirectory) "${LinuxDirs.GUEST_PROJECTS}/$name" else LinuxDirs.GUEST_PROJECTS

    companion object {
        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        /** Why [name] cannot be a project's name, in the owner's words; null when it can. */
        fun problem(name: String): String? = when {
            name.isBlank() -> "Give the project a name."
            !NAME.matches(name) -> "Use letters, digits, dot, dash and underscore only, starting with a letter or digit."
            else -> null
        }
    }
}
