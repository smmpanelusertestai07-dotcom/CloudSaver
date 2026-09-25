package com.pocketide.linux

import com.pocketide.core.Clock
import com.pocketide.model.Decision
import java.io.File
import java.net.InetAddress

/** A phone as set-up sees it, with everything scripted: data rules, DNS, free space. */
internal class FakeSetupHost : SetupHost {
    var decision: Decision = Decision.YES
    var dns: List<InetAddress> = listOf(InetAddress.getByName("192.168.1.1"))
    var free: Long = 64_000_000_000L
    val asked = mutableListOf<Pair<Long, String>>()
    val transfers = mutableListOf<Pair<Long, String>>()

    override fun dnsServers() = dns
    override fun allow(bytes: Long, kind: String): Decision = decision.also { asked += bytes to kind }
    override fun transferred(bytes: Long, kind: String) {
        transfers += bytes to kind
    }
    override fun freeBytes() = free
}

/** Serves prepared archives by URL, the way a verified download would leave them. */
internal class FakeFetcher : Fetcher {
    val archives = mutableMapOf<String, ByteArray>()
    val fetched = mutableListOf<String>()
    var mismatch: String? = null

    override suspend fun fetch(pin: PinnedDownload, target: File, kind: String, onProgress: (Long) -> Unit): File {
        fetched += pin.url
        if (pin.url == mismatch) throw ChecksumMismatch(pin.fileName)
        target.parentFile?.mkdirs()
        target.writeBytes(archives.getValue(pin.url))
        onProgress(pin.bytes / 2)
        onProgress(pin.bytes)
        return target
    }
}

internal class FakeAssets(var bootstrap: String = "#!/bin/bash\n# bootstrap v1\n") : LinuxAssets {
    override fun names() = listOf("bootstrap.sh", "launch.pl", "update.sh")
    override fun read(name: String): ByteArray = when (name) {
        "bootstrap.sh" -> bootstrap
        "update.sh" -> "#!/bin/bash\n# update\n"
        "launch.pl" -> "#!/usr/bin/perl\n"
        else -> error(name)
    }.toByteArray()
}

/**
 * Plays the programs set-up runs inside Linux: bootstrap.sh (which leaves its stamp),
 * update.sh, and `code-server --version` (which answers from the unpacked release).
 */
internal class FakeGuest : GuestRunner {
    val ran = mutableListOf<List<String>>()
    var bootstrapFails = false
    var missingTools = 13
    var securityFixes = 2
    var linkStarts = true

    override suspend fun run(root: File, argv: List<String>, onLine: (String) -> Unit): Int {
        ran += argv
        val guest = GuestRoot(root)
        return when {
            argv == listOf("/bin/bash", "/opt/pocketide/bootstrap.sh") -> bootstrap(guest, onLine)
            argv == listOf("/bin/bash", "/opt/pocketide/update.sh") -> {
                onLine("Checking Ubuntu's security fixes…")
                onLine("Fetched 2.0 MB in 1s (2000 kB/s)")
                onLine("pocketide-fixed $securityFixes")
                0
            }
            argv.size == 2 && argv[1] == "--version" && argv[0].endsWith("/bin/code-server") -> {
                if (argv[0].startsWith("/opt/code-server/") && !linkStarts) return 1
                val answer = guest.readText(argv[0])?.trim() ?: return 127
                if (!answer.startsWith("code-server ")) return 1
                onLine("[2026-09-24T16:39:25.049Z] info  Wrote default config file to /root/.config/code-server/config.yaml")
                onLine("${answer.removePrefix("code-server ")} 59c988c7 with Code 1.138.0")
                0
            }
            else -> 127
        }
    }

    private fun bootstrap(guest: GuestRoot, onLine: (String) -> Unit): Int {
        onLine("pocketide-step Preparing Ubuntu…")
        onLine("pocketide-progress 10")
        if (bootstrapFails) {
            onLine("Could not reach Ubuntu's servers.")
            return 1
        }
        onLine("dlstatus:1:50.0:Retrieving file 1 of 2")
        onLine("Fetched 1.5 MB in 2s (750 kB/s)")
        onLine("pocketide-progress 30")
        onLine("pocketide-step Installing tools…")
        onLine("update-alternatives: warning: skip creation of /usr/share/man/man1/pico.1.gz because associated file is missing" + "x".repeat(100))
        onLine("pmstatus:git:60.0:Installing git (arm64)")
        onLine("pocketide-installed $missingTools")
        missingTools = 0
        onLine("pocketide-progress 100")
        guest.write("/opt/pocketide/bootstrap.stamp", "version=1\n".toByteArray())
        return 0
    }
}

/** Everything a [ComputerSetup] needs, in a temporary folder laid out like the app's. */
internal class SetupWorld(base: File) {
    val rootfs = File(base, "files/rootfs")
    val downloads = File(base, "cache/downloads")
    val record = File(base, "files/computer.json")
    val rooms = File(base, "files/rooms")
    val host = FakeSetupHost()
    val fetcher = FakeFetcher()
    val guest = FakeGuest()
    val assets = FakeAssets()
    val published = mutableListOf<ComputerState>()

    val ubuntuArchive = TarBuilder()
        .dir("etc/")
        .file("etc/os-release", "PRETTY_NAME=\"Ubuntu 24.04.5 LTS\"\nVERSION_ID=\"24.04\"\n")
        .file("etc/hosts", "")
        .file("etc/gai.conf", "# the stock file\n")
        .file("etc/hostname", "buildd\n")
        .dir("usr/bin/")
        .file("usr/bin/perl", "perl", mode = 0b111_101_101)
        .hardlink("usr/bin/perl5.38.2", "usr/bin/perl")
        .symlink("bin", "usr/bin")
        .dir("opt/")
        .gz()
    val ubuntu = PinnedDownload("https://cdimage.example/ubuntu-base-arm64.tar.gz", Downloader.sha256(ubuntuArchive), ubuntuArchive.size.toLong())

    val codeServer = codeServerRelease("4.138.0")

    init {
        fetcher.archives[ubuntu.url] = ubuntuArchive
    }

    /** A release laid out like code-server's: one top folder, the launcher and package.json. */
    fun codeServerRelease(version: String, starts: Boolean = true): CodeServerPin {
        val top = "code-server-$version-linux-arm64"
        val archive = TarBuilder()
            .dir("$top/")
            .file("$top/bin/code-server", if (starts) "code-server $version\n" else "broken\n", mode = 0b111_101_101)
            .file("$top/package.json", "{\"name\":\"code-server\",\"version\":\"$version\"}")
            .gz()
        val url = "https://github.example/code-server-$version-linux-arm64.tar.gz"
        fetcher.archives[url] = archive
        return CodeServerPin(version, url, Downloader.sha256(archive), archive.size.toLong())
    }

    fun setup(ubuntuSupportEnds: Long = LinuxPins.UBUNTU_SUPPORT_ENDS) = ComputerSetup(
        places = SetupPlaces(rootfs, downloads, record),
        host = host,
        fetcher = fetcher,
        runner = guest,
        assets = assets,
        clock = Clock { NOW },
        publish = { published += it },
        ubuntu = ubuntu,
        ubuntuVersion = "24.04.5",
        codeServer = codeServer,
        ubuntuSupportEnds = ubuntuSupportEnds,
    )

    fun savedRecord(): SetupRecord = RecordStore(record).load()

    fun guestText(path: String): String? = GuestRoot(rootfs).readText(path)

    companion object {
        const val NOW = 1_790_000_000_000L
    }
}
