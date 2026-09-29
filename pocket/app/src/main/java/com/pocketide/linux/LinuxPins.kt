package com.pocketide.linux

import java.io.File

/** A file fetched only from [url] and kept only when it is [bytes] long with this [sha256]. */
data class PinnedDownload(val url: String, val sha256: String, val bytes: Long) {
    val fileName: String get() = url.substringAfterLast('/')
}

/** A code-server release for this phone's processor, used only when its SHA-256 matches. */
data class CodeServerPin(val version: String, val url: String, val sha256: String, val bytes: Long)

internal fun CodeServerPin.download() = PinnedDownload(url, sha256, bytes)

/**
 * The processor the computer is built for, named the way each publisher names it. Phones are
 * arm64; x86-64 is for the Android emulator the end-to-end tests run on.
 */
enum class Arch(val ubuntu: String, val codeServer: String, val openVsx: String) {
    ARM64("arm64", "arm64", "linux-arm64"),
    X86_64("amd64", "amd64", "linux-x64"),
    ;

    companion object {
        /**
         * The processor of the PRoot this APK installed: Android picks one ABI's native libraries
         * at install time and puts them in a folder named after it (lib/arm64, lib/x86_64).
         */
        fun of(nativeLibraryDir: File): Arch? = when (nativeLibraryDir.name) {
            "arm64", "arm64-v8a" -> ARM64
            "x86_64" -> X86_64
            else -> null
        }
    }
}

/**
 * What a new or reset computer is built from. Each checksum was taken from the publisher's own
 * source and matched against a fresh download (29 Sep 2026): Ubuntu's from the SHA256SUMS file
 * signed by the Ubuntu CD image key, code-server's from its GitHub release's asset digest.
 * tools/check-pins.py downloads each one again in CI.
 */
object LinuxPins {
    const val UBUNTU_VERSION = "26.04.1"

    /** Ubuntu 26.04 LTS, "Resolute Raccoon": the codename apt's package sources use. */
    const val UBUNTU_CODENAME = "resolute"

    /**
     * When Canonical's standard security fixes for Ubuntu 26.04 end: after April 2031 (UTC epoch
     * ms of 1 June 2031). From then on the Computer screen says so, and an app update that pins the
     * next LTS moves the computer to it with Reset.
     */
    const val UBUNTU_SUPPORT_ENDS = 1_938_038_400_000L

    // The point release's own folder: the plain 26.04 one moves on to each newer point release.
    private const val UBUNTU_BASE = "https://cdimage.ubuntu.com/ubuntu-base/releases/$UBUNTU_VERSION/release"

    fun ubuntuBase(arch: Arch): PinnedDownload = when (arch) {
        Arch.ARM64 -> PinnedDownload(
            url = "$UBUNTU_BASE/ubuntu-base-26.04.1-base-arm64.tar.gz",
            sha256 = "5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd",
            bytes = 35_092_106,
        )
        Arch.X86_64 -> PinnedDownload(
            url = "$UBUNTU_BASE/ubuntu-base-26.04.1-base-amd64.tar.gz",
            sha256 = "a496a960472ce474a59590b8987d3a1135d3cbef1991f3b1abe8cacfea8bf85a",
            bytes = 34_931_253,
        )
    }

    const val CODE_SERVER_VERSION = "4.139.1"
    private const val CODE_SERVER_RELEASE = "https://github.com/coder/code-server/releases/download/v$CODE_SERVER_VERSION"

    fun codeServer(arch: Arch): CodeServerPin = when (arch) {
        Arch.ARM64 -> CodeServerPin(
            version = CODE_SERVER_VERSION,
            url = "$CODE_SERVER_RELEASE/code-server-$CODE_SERVER_VERSION-linux-arm64.tar.gz",
            sha256 = "0edb4b60d9c4744b2dd14b0911e3c2e6dd8c6f3c13bd58bda23ae744e59e7df1",
            bytes = 216_952_063,
        )
        Arch.X86_64 -> CodeServerPin(
            version = CODE_SERVER_VERSION,
            url = "$CODE_SERVER_RELEASE/code-server-$CODE_SERVER_VERSION-linux-amd64.tar.gz",
            sha256 = "53029be6c5781b7bca49b815fcc9a2a3fc111813ad8c9965b2c0f0d2985a0674",
            bytes = 222_167_474,
        )
    }
}
