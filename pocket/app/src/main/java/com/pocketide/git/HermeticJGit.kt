package com.pocketide.git

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader

/**
 * JGit's window on the phone. There is no system git on Android and `user.home` may be empty, and
 * nothing outside the app may change how git behaves here: no user, system or JGit config file is
 * read, no environment variable is consulted, and no `git` process is started to find a system
 * config. Installed once per process; installing again changes nothing.
 */
internal object HermeticJGit {
    private val lock = Any()

    fun install() {
        synchronized(lock) {
            val current = SystemReader.getInstance()
            if (current !is HermeticSystemReader) {
                SystemReader.setInstance(HermeticSystemReader(current))
            }
            // Otherwise the first git call on a new file system can block for seconds while JGit
            // measures its timestamp resolution.
            FS.FileStoreAttributes.setBackground(true)
        }
    }
}

private class HermeticSystemReader(private val base: SystemReader) : SystemReader() {
    // The default looks the name up on the network; git only uses it for fallback identities.
    override fun getHostname(): String = "localhost"

    override fun getenv(variable: String?): String? = null

    override fun getProperty(key: String?): String? = base.getProperty(key)

    override fun openUserConfig(parent: Config?, fs: FS?): FileBasedConfig = InMemoryConfig(parent)

    override fun openSystemConfig(parent: Config?, fs: FS?): FileBasedConfig = InMemoryConfig(parent)

    override fun openJGitConfig(parent: Config?, fs: FS?): FileBasedConfig = InMemoryConfig(parent)

    override fun getCurrentTime(): Long = base.currentTime

    override fun getTimezone(time: Long): Int = base.getTimezone(time)
}

/** An empty config that lives only in memory: loading and saving do nothing. */
private class InMemoryConfig(parent: Config?) : FileBasedConfig(parent, null, null) {
    override fun load() = Unit

    override fun save() = Unit

    override fun isOutdated(): Boolean = false

    override fun toString(): String = "in-memory config"
}
