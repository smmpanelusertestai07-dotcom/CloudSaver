package com.pocketide.linux

import com.pocketide.agents.SemVer
import com.pocketide.github.PublicRelease

/**
 * code-server releases newer than the one this app pins, so the computer's VS Code keeps up with
 * the agents' extensions between app updates: an extension that asks for a newer VS Code would
 * otherwise stop updating, and in time stop working, until a new APK arrived.
 *
 * Trusted exactly as far as the pin is: the pin's checksum is the digest GitHub computed for the
 * release asset, and a release from here is used only with that same digest, from the same
 * repository and download address. Only stable releases of the pin's own major version are taken:
 * a new major may change how code-server is started, and comes with an app update. The switch
 * itself is [ComputerSetup.updateCodeServer]'s: unpacked beside the old one, checked, and
 * switched back when it does not start.
 */
internal object CodeServerChannel {
    const val REPOSITORY = "coder/code-server"

    /** Well above code-server's arm64 archive (about 225 MB in 2026), far below anything absurd. */
    private const val MAX_BYTES = 600_000_000L
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** [latest] as a pin when it is a usable release newer than [floor]; null otherwise. */
    fun newer(latest: PublicRelease, floor: CodeServerPin): CodeServerPin? {
        if (latest.prerelease) return null
        val version = SemVer.parse(latest.tag)?.takeIf { !it.isPreRelease } ?: return null
        val pinned = SemVer.parse(floor.version) ?: return null
        if (version.major != pinned.major || version <= pinned) return null
        val name = "code-server-$version-linux-arm64.tar.gz"
        val url = "https://github.com/$REPOSITORY/releases/download/v$version/$name"
        val asset = latest.assets.singleOrNull { it.name == name && it.downloadUrl == url } ?: return null
        val sha256 = asset.sha256?.takeIf(SHA256::matches) ?: return null
        if (asset.bytes !in 1..MAX_BYTES) return null
        return CodeServerPin(version = version.toString(), url = url, sha256 = sha256, bytes = asset.bytes)
    }
}
