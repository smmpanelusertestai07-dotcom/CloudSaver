package com.pocketide.linux

/** A file fetched only from [url] and kept only when it is [bytes] long with this [sha256]. */
data class PinnedDownload(val url: String, val sha256: String, val bytes: Long) {
    val fileName: String get() = url.substringAfterLast('/')
}

/**
 * What a new or reset computer is built from. Each checksum was taken from the publisher's own
 * source and matched against a fresh download (checked 24 Sep 2026).
 */
object LinuxPins {
    const val UBUNTU_VERSION = "24.04.5"

    /**
     * When Canonical's standard security fixes for Ubuntu 24.04 end: after May 2029 (UTC epoch
     * ms of 1 June 2029). From then on the computer says so, and an app update that pins the
     * next LTS rebuilds it with Reset.
     */
    const val UBUNTU_SUPPORT_ENDS = 1_874_966_400_000L

    /** Canonical's base image; SHA256SUMS beside it is signed by the Ubuntu CD image key. */
    val ubuntuBase = PinnedDownload(
        url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
        sha256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
        bytes = 29_936_675,
    )

    /** The release's asset digest on GitHub; the release publishes no checksum file. */
    val codeServer = CodeServerPin(
        version = "4.138.0",
        url = "https://github.com/coder/code-server/releases/download/v4.138.0/code-server-4.138.0-linux-arm64.tar.gz",
        sha256 = "fbebf4b18e97a5a48b7b105be0161d411e54ccfb53a1ffb1673c16518024fa30",
        bytes = 225_430_592,
    )
}

internal fun CodeServerPin.download() = PinnedDownload(url, sha256, bytes)
