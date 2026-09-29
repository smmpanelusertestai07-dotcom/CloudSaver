package com.pocketide.linux

import com.pocketide.core.Clock
import com.pocketide.core.SemVer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** What set-up needs from the phone, kept small so the steps can run off the phone in tests. */
internal interface SetupHost {
    fun dnsServers(): List<InetAddress>

    /** Bytes the computer may still use, cached files Android can clear included. */
    fun freeBytes(): Long

    /** Asks Android to clear cached files until [bytes] fit, after [freeBytes] said they would. */
    fun makeRoom(bytes: Long)
}

/** Where set-up keeps things: the rootfs, the one being unpacked beside it, downloads, and its record. */
internal class SetupPlaces(val rootfs: File, val downloads: File, val record: File) {
    val staging = File(rootfs.parentFile, rootfs.name + ".partial")
}

/** An expected stop, already in the owner's words. */
internal class SetupStop(val why: String, val fix: String) : Exception(why)

/**
 * Builds, repairs, updates and removes the computer. Every step checks what is already done (the
 * [SetupRecord], the files themselves), so running it again after a kill continues where it
 * stopped, and running it on a finished computer only refreshes what the phone provides.
 *
 * Callers run one operation at a time.
 */
@Suppress("LongParameterList") // Each part is replaced in tests: the downloads, the runner, the pins.
internal class ComputerSetup(
    private val places: SetupPlaces,
    private val host: SetupHost,
    private val fetcher: Fetcher,
    runner: GuestRunner,
    assets: LinuxAssets,
    private val clock: Clock,
    private val publish: (ComputerState) -> Unit,
    private val arch: Arch,
    private val ubuntu: PinnedDownload = LinuxPins.ubuntuBase(arch),
    private val ubuntuVersion: String = LinuxPins.UBUNTU_VERSION,
    private val codeServer: CodeServerPin = LinuxPins.codeServer(arch),
    private val extractor: TarGzExtractor = TarGzExtractor(),
) {
    private val records = RecordStore(places.record)
    private val scripts = GuestScripts(assets, runner)
    private val slot = CodeServerSlot(places.rootfs, extractor, runner)

    /** What the files on disk say, for when the app starts. */
    fun stateOnDisk(): ComputerState {
        val record = records.load()
        return when {
            record.readyAt == null -> ComputerState.NotInstalled
            healthy(record) -> ComputerState.Ready
            else -> ComputerState.Broken("Part of the computer is missing.", FIX_SET_UP_AGAIN)
        }
    }

    fun record(): SetupRecord = records.load()

    /** Sets up what is missing; returns (and has published) where the computer ended up. */
    suspend fun install(): ComputerState = withContext(Dispatchers.IO) {
        val end = try {
            Trees.sweep(places.rootfs)
            val record = records.load()
            if (record.readyAt != null && healthy(record)) {
                refreshGuest(places.rootfs)
            } else {
                build(record.copy(readyAt = null))
            }
            ComputerState.Ready
        } catch (cancelled: CancellationException) {
            publish(stateOnDisk())
            throw cancelled
        } catch (stop: SetupStop) {
            ComputerState.Broken(stop.why, stop.fix)
        } catch (failure: Exception) {
            broken(failure)
        }
        publish(end)
        end
    }

    /**
     * A computer that is not whole is set up again from where it stopped; a whole one has each
     * part checked and, where it can be, put right. Files the owner may have changed are only
     * ever written when missing.
     */
    suspend fun repair(): List<RepairItem> = withContext(Dispatchers.IO) {
        val record = records.load()
        if (record.readyAt == null || !healthy(record)) {
            return@withContext when (val end = install()) {
                ComputerState.Ready -> listOf(RepairItem(WHOLE, RepairStatus.NEW, "The parts that were missing are set up again."))
                is ComputerState.Broken -> listOf(RepairItem(WHOLE, RepairStatus.WARN, "${end.why} ${end.fix}"))
                else -> listOf(RepairItem(WHOLE, RepairStatus.WARN, "Set-up did not finish. Try again."))
            }
        }
        publish(ComputerState.Updating("Repair"))
        try {
            guestFiles() + listOf(tools(), codeServerItem(record)) + listOfNotNull(supportEnded(record))
        } finally {
            publish(stateOnDisk())
        }
    }

    private fun guestFiles(): List<RepairItem> {
        val guest = GuestRoot(places.rootfs)
        val restored = GuestConfig.writeBasics(guest, onlyMissing = true)
        val basics = if (restored.isEmpty()) {
            RepairItem(NETWORK_FILES, RepairStatus.OK, "Present; left as they are.")
        } else {
            RepairItem(NETWORK_FILES, RepairStatus.NEW, "Written again: ${restored.joinToString()}.")
        }
        val resolver = if (GuestConfig.writeResolver(guest, host.dnsServers())) {
            RepairItem("DNS", RepairStatus.OK, "Linux uses the phone's DNS servers.")
        } else {
            RepairItem("DNS", RepairStatus.WARN, "The phone reported no DNS servers. Check the connection, then repair again.")
        }
        val replaced = scripts.install(places.rootfs)
        val own = if (replaced == 0) {
            RepairItem(SCRIPTS, RepairStatus.OK, "Up to date.")
        } else {
            RepairItem(SCRIPTS, RepairStatus.NEW, "Replaced $replaced with the app's own copies.")
        }
        return listOf(basics, resolver, own)
    }

    /** bootstrap.sh again: it installs only the tools that are missing and resets the settings it owns. */
    private suspend fun tools(): RepairItem {
        var installed = 0
        val result = scripts.run(places.rootfs, GuestScripts.BOOTSTRAP) { line ->
            if (line is GuestLine.Installed) installed = line.count
        }
        if (result.exitCode != 0) {
            return RepairItem(TOOLS, RepairStatus.WARN, "Stopped: ${stoppedBecause(result)}. Try again on a steady connection.")
        }
        records.save(records.load().copy(bootstrap = scripts.bootstrapVersion()))
        return when (installed) {
            0 -> RepairItem(TOOLS, RepairStatus.OK, "Everything is installed.")
            1 -> RepairItem(TOOLS, RepairStatus.NEW, "Installed 1 missing tool.")
            else -> RepairItem(TOOLS, RepairStatus.NEW, "Installed $installed missing tools.")
        }
    }

    /** Ubuntu past the end of its security fixes, which only a computer built on the next LTS gets again. */
    private fun supportEnded(record: SetupRecord): RepairItem? {
        if (clock.now() < LinuxPins.UBUNTU_SUPPORT_ENDS || record.ubuntu != ubuntuVersion) return null
        val release = ubuntuVersion.split('.').take(2).joinToString(".")
        return RepairItem(SUPPORT, RepairStatus.WARN, "Ubuntu $release no longer gets security fixes. $FIX_NEXT_LTS")
    }

    /** code-server is checked by starting it; one that does not start is unpacked again from the pinned release. */
    private suspend fun codeServerItem(record: SetupRecord): RepairItem {
        val version = record.codeServer ?: codeServer.version
        if (slot.current() == version && slot.reports(CodeServerSlot.LINK, version)) {
            return RepairItem(CODE_SERVER, RepairStatus.OK, "Version $version starts.")
        }
        val pin = codeServer
        val archive = fetcher.fetch(pin.download(), File(places.downloads, pin.download().fileName)) {}
        slot.unpack(pin.version, archive) {}
        val previous = slot.switchTo(pin.version)
        if (!slot.reports(CodeServerSlot.LINK, pin.version)) {
            previous?.takeIf { it != CodeServerSlot.folder(pin.version) }?.let(slot::switchBack)
            return RepairItem(CODE_SERVER, RepairStatus.WARN, "It does not start, even unpacked again. $FIX_RESET")
        }
        records.save(records.load().copy(codeServer = pin.version, codeServerSha256 = pin.sha256))
        slot.prune(keep = pin.version)
        Files.deleteIfExists(archive.toPath())
        return RepairItem(CODE_SERVER, RepairStatus.NEW, "Unpacked version ${pin.version} again; it starts.")
    }

    private fun movesForward(installed: String?, wanted: String): Boolean {
        if (installed == wanted) return false
        val from = installed?.let(SemVer::parse) ?: return true
        val to = SemVer.parse(wanted) ?: return false
        return to > from
    }

    private fun stoppedBecause(result: ScriptResult) = result.lastWords?.removeSuffix(".") ?: "exit code ${result.exitCode}"

    /** Deletes Ubuntu and what set-up downloaded; the record goes first, so a kill never leaves "ready". */
    suspend fun remove() = withContext(Dispatchers.IO) {
        records.clear()
        Trees.discard(places.staging)
        Trees.discard(places.rootfs)
        Trees.sweep(places.staging)
        Trees.sweep(places.rootfs)
        Trees.delete(places.downloads.toPath())
        publish(ComputerState.NotInstalled)
    }

    /** Ubuntu's updates, after running a bootstrap an app update changed. */
    suspend fun updateBase(): UpdateOutcome = withContext(Dispatchers.IO) {
        val record = records.load()
        if (record.readyAt == null || !healthy(record)) return@withContext UpdateOutcome.Waiting(NOT_SET_UP)
        publish(ComputerState.Updating("Ubuntu's updates"))
        try {
            refreshGuest(places.rootfs)
            val refreshed = record.bootstrap != scripts.bootstrapVersion()
            if (refreshed) {
                val result = scripts.run(places.rootfs, GuestScripts.BOOTSTRAP) {}
                if (result.exitCode != 0) {
                    return@withContext UpdateOutcome.Failed("Refreshing Ubuntu stopped: ${stoppedBecause(result)}.")
                }
                records.save(records.load().copy(bootstrap = scripts.bootstrapVersion()))
            }
            var updated = 0
            val result = scripts.run(places.rootfs, GuestScripts.UPDATE) { line ->
                if (line is GuestLine.Fixed) updated = line.count
            }
            if (result.exitCode != 0) {
                return@withContext UpdateOutcome.Failed("Ubuntu's updates stopped: ${stoppedBecause(result)}.")
            }
            records.save(records.load().copy(updatedAt = clock.now()))
            baseOutcome(refreshed, updated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            UpdateOutcome.Failed(broken(failure).why)
        } finally {
            publish(stateOnDisk())
        }
    }

    /**
     * Moves code-server to [pin], never to an older version than the one in place. [hold] stops new
     * programs from starting and answers false while code-server is running; [release] opens them
     * again.
     */
    suspend fun updateCodeServer(pin: CodeServerPin = codeServer, hold: () -> Boolean, release: () -> Unit): UpdateOutcome =
        withContext(Dispatchers.IO) {
            val record = records.load()
            if (record.readyAt == null || !healthy(record)) return@withContext UpdateOutcome.Waiting(NOT_SET_UP)
            if (!movesForward(record.codeServer, pin.version)) return@withContext UpdateOutcome.UpToDate
            val staged = slot.unpacked(pin.version)
            if (!staged && host.freeBytes() < pin.bytes + CODE_SERVER_SPACE) {
                return@withContext UpdateOutcome.Failed("There is not enough free space to update code-server.")
            }
            if (!staged) host.makeRoom(pin.bytes + CODE_SERVER_SPACE)
            publish(ComputerState.Updating("code-server ${pin.version}"))
            try {
                switchCodeServer(record, pin, staged, hold, release)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                UpdateOutcome.Failed(broken(failure).why)
            } finally {
                publish(stateOnDisk())
            }
        }

    private suspend fun switchCodeServer(
        record: SetupRecord,
        pin: CodeServerPin,
        staged: Boolean,
        hold: () -> Boolean,
        release: () -> Unit,
    ): UpdateOutcome {
        val archive = File(places.downloads, pin.download().fileName)
        if (!staged) {
            fetcher.fetch(pin.download(), archive) {}
            slot.unpack(pin.version, archive) {}
            Files.deleteIfExists(archive.toPath())
        }
        val stays = "code-server ${record.codeServer} stays."
        if (!slot.reports("/opt/${CodeServerSlot.folder(pin.version)}", pin.version)) {
            slot.delete(pin.version)
            return UpdateOutcome.Failed("code-server ${pin.version} did not start, so $stays")
        }
        // Kept unpacked: the switch is tried again when code-server is not running.
        if (!hold()) return UpdateOutcome.Waiting("code-server ${pin.version} is ready; it switches over the next time the computer starts.")
        try {
            val previous = slot.switchTo(pin.version)
            if (!slot.reports(CodeServerSlot.LINK, pin.version)) {
                previous?.let(slot::switchBack)
                slot.delete(pin.version)
                return UpdateOutcome.Failed("code-server ${pin.version} did not start after the switch, so $stays")
            }
            records.save(records.load().copy(codeServer = pin.version, codeServerSha256 = pin.sha256))
            slot.prune(keep = pin.version)
        } finally {
            release()
        }
        return UpdateOutcome.Updated("code-server is now ${pin.version}.")
    }

    private suspend fun build(start: SetupRecord) {
        records.save(start)
        var record = start
        val baseInPlace = record.base != null && GuestRoot(places.rootfs).existing("/etc/os-release") != null
        val toolsDone = baseInPlace && record.bootstrap == scripts.bootstrapVersion() &&
            GuestRoot(places.rootfs).existing(GuestScripts.STAMP) != null
        val codeServerDone = baseInPlace && record.codeServer != null && slot.installed() &&
            slot.current() == record.codeServer
        checkRoom(baseInPlace, toolsDone, codeServerDone)
        val bar = Bar(
            stages = buildSet {
                if (!baseInPlace) addAll(listOf(Stage.DOWNLOAD_UBUNTU, Stage.UNPACK_UBUNTU))
                if (!toolsDone) add(Stage.TOOLS)
                if (!codeServerDone) addAll(listOf(Stage.DOWNLOAD_CODE_SERVER, Stage.UNPACK_CODE_SERVER, Stage.CHECK))
            },
            bytesTotal = (if (baseInPlace) 0 else ubuntu.bytes) + (if (codeServerDone) 0 else codeServer.bytes),
            publish = publish,
        )
        if (!baseInPlace) record = placeUbuntu(bar)
        refreshGuest(places.rootfs)
        if (!toolsDone) record = runBootstrap(record, bar)
        if (!codeServerDone) record = placeCodeServer(record, bar)
        records.save(record.copy(readyAt = clock.now(), updatedAt = clock.now()))
        publish(ComputerState.Ready)
    }

    /** Free space, checked for what this run still has to fetch and unpack. */
    private fun checkRoom(baseInPlace: Boolean, toolsDone: Boolean, codeServerDone: Boolean) {
        val space = (if (baseInPlace) 0 else UBUNTU_SPACE) +
            (if (toolsDone) 0 else TOOLS_SPACE) +
            (if (codeServerDone) 0 else CODE_SERVER_SPACE)
        if (space > 0 && host.freeBytes() < space + SPARE_SPACE) {
            val needed = (space + SPARE_SPACE) / BYTES_PER_GB
            throw SetupStop(
                "There is not enough free space for the computer.",
                "Free about ${"%.1f".format(needed)} GB on the phone, then tap Set up again. It continues where it stopped.",
            )
        }
        if (space > 0) host.makeRoom(space + SPARE_SPACE)
    }

    private suspend fun placeUbuntu(bar: Bar): SetupRecord {
        bar.enter(Stage.DOWNLOAD_UBUNTU, "Downloading Ubuntu $ubuntuVersion…")
        val archive = fetcher.fetch(ubuntu, File(places.downloads, ubuntu.fileName)) { bar.downloaded(it, ubuntu.bytes) }
        bar.downloadFinished(ubuntu.bytes)
        bar.enter(Stage.UNPACK_UBUNTU, "Unpacking Ubuntu…")
        Trees.delete(places.staging.toPath())
        extractor.extract(archive, places.staging) { bar.within(it) }
        val guest = GuestRoot(places.staging)
        GuestConfig.writeBasics(guest, onlyMissing = false)
        GuestConfig.writeResolver(guest, host.dnsServers())
        scripts.install(places.staging)
        // PRoot's link2symlink writes absolute host paths into the links it makes, so the tree must
        // be in its final place before anything runs inside it; moved later, those links would
        // point at a folder that no longer exists.
        Trees.discard(places.rootfs)
        Files.move(places.staging.toPath(), places.rootfs.toPath(), StandardCopyOption.ATOMIC_MOVE)
        val record = SetupRecord(arch = arch.name, ubuntu = ubuntuVersion, base = ubuntu.sha256)
        records.save(record)
        Files.deleteIfExists(archive.toPath())
        return record
    }

    private suspend fun runBootstrap(record: SetupRecord, bar: Bar): SetupRecord {
        bar.enter(Stage.TOOLS, "Preparing Ubuntu…")
        val progress = ToolsProgress(bar)
        val result = scripts.run(places.rootfs, GuestScripts.BOOTSTRAP) { line -> progress.show(line) }
        if (result.exitCode != 0) {
            throw SetupStop("Setting up Ubuntu stopped: ${stoppedBecause(result)}.", FIX_TRY_AGAIN)
        }
        return record.copy(bootstrap = scripts.bootstrapVersion()).also(records::save)
    }

    private suspend fun placeCodeServer(record: SetupRecord, bar: Bar): SetupRecord {
        val pin = codeServer
        bar.enter(Stage.DOWNLOAD_CODE_SERVER, "Downloading code-server ${pin.version}…")
        val archive = fetcher.fetch(pin.download(), File(places.downloads, pin.download().fileName)) { bar.downloaded(it, pin.bytes) }
        bar.downloadFinished(pin.bytes)
        bar.enter(Stage.UNPACK_CODE_SERVER, "Unpacking code-server…")
        slot.unpack(pin.version, archive) { bar.within(it) }
        slot.switchTo(pin.version)
        bar.enter(Stage.CHECK, "Checking that code-server starts…")
        if (!slot.reports(CodeServerSlot.LINK, pin.version)) throw SetupStop("code-server did not start.", FIX_RESET)
        slot.prune(keep = pin.version)
        val updated = record.copy(codeServer = pin.version, codeServerSha256 = pin.sha256)
        records.save(updated)
        Files.deleteIfExists(archive.toPath())
        return updated
    }

    /** What the phone provides and an app update may change: DNS, host names, the scripts. */
    private fun refreshGuest(rootfs: File) {
        val guest = GuestRoot(rootfs)
        GuestConfig.writeBasics(guest, onlyMissing = true)
        GuestConfig.writeResolver(guest, host.dnsServers())
        scripts.install(rootfs)
    }

    private fun healthy(record: SetupRecord): Boolean =
        record.base != null && record.codeServer != null &&
            GuestRoot(places.rootfs).existing("/etc/os-release") != null && slot.installed()

    private fun baseOutcome(refreshed: Boolean, updated: Int): UpdateOutcome {
        val packages = if (updated == 1) "1 package" else "$updated packages"
        return when {
            refreshed && updated > 0 -> UpdateOutcome.Updated("Refreshed Ubuntu's set-up and updated $packages.")
            refreshed -> UpdateOutcome.Updated("Refreshed Ubuntu's set-up.")
            updated > 0 -> UpdateOutcome.Updated("Updated $packages.")
            else -> UpdateOutcome.UpToDate
        }
    }

    /** An unexpected failure, in the owner's words. */
    private fun broken(failure: Exception): ComputerState.Broken = when {
        failure is ChecksumMismatch ->
            ComputerState.Broken("The download of ${failure.fileName} did not match its published checksum, so it was deleted.", FIX_TRY_AGAIN)
        failure is DownloadFailed -> ComputerState.Broken("${failure.message}.", FIX_CONNECTION)
        failure is UnsafeArchive -> ComputerState.Broken("A downloaded archive was not safe to unpack.", FIX_TRY_AGAIN)
        Downloader.outOfSpace(failure) -> ComputerState.Broken("The phone ran out of space.", "Free some space on the phone, then tap Set up again.")
        failure is FileSystemException -> ComputerState.Broken("Set-up could not write its files.", FIX_RESET)
        failure is IOException -> ComputerState.Broken("Set-up stopped: ${failure.message ?: "a file could not be read or written"}.", FIX_TRY_AGAIN)
        else -> ComputerState.Broken("Set-up stopped unexpectedly.", FIX_RESET)
    }

    private enum class Stage(val weight: Int) {
        DOWNLOAD_UBUNTU(5),
        UNPACK_UBUNTU(7),
        TOOLS(48),
        DOWNLOAD_CODE_SERVER(20),
        UNPACK_CODE_SERVER(16),
        CHECK(4),
    }

    /** One bar across the stages this run still has to do; it never moves backwards. */
    private class Bar(stages: Set<Stage>, private val bytesTotal: Long, private val publish: (ComputerState) -> Unit) {
        private val total = stages.sumOf { it.weight }.coerceAtLeast(1)
        private var finished = 0
        private var current: Stage? = null
        private var within = 0f
        private var step = ""
        private var bytesBefore = 0L
        private var bytes = 0L

        fun enter(stage: Stage, text: String) {
            current?.let { finished += it.weight }
            current = stage
            within = 0f
            step = text
            show()
        }

        fun step(text: String) {
            step = text
            show()
        }

        fun within(fraction: Float) {
            within = maxOf(within, fraction.coerceIn(0f, 1f))
            show()
        }

        fun downloaded(done: Long, size: Long) {
            bytes = bytesBefore + done
            within(done.toFloat() / size.coerceAtLeast(1))
        }

        fun downloadFinished(size: Long) {
            bytesBefore += size
            bytes = bytesBefore
        }

        private fun show() {
            val weight = current?.weight ?: 0
            val fraction = ((finished + weight * within) / total).coerceIn(0f, 1f)
            publish(ComputerState.Installing(step, fraction, bytes, bytesTotal))
        }
    }

    /** Turns bootstrap.sh's markers and apt's status lines into the bar's position and step. */
    private class ToolsProgress(private val bar: Bar) {
        private var phase = 0
        private var label = "Preparing Ubuntu"

        fun show(line: GuestLine) {
            when (line) {
                is GuestLine.Progress -> {
                    phase = line.percent
                    bar.within(line.percent / 100f)
                }
                is GuestLine.Apt -> {
                    val part = line.percent / 100f
                    val position = when {
                        phase < APT_LISTS_DONE -> 10 + 20 * part
                        !line.installing -> 30 + 25 * part
                        else -> 55 + 35 * part
                    }
                    bar.step("$label: ${line.text}")
                    bar.within(position / 100f)
                }
                // Only the script's own steps name the bar: apt and dpkg print warnings too.
                is GuestLine.Step -> {
                    label = line.text.removeSuffix("…")
                    bar.step(line.text)
                }
                is GuestLine.Text, is GuestLine.Fetched, is GuestLine.Fixed, is GuestLine.Installed -> Unit
            }
        }

        private companion object {
            const val APT_LISTS_DONE = 30
        }
    }

    internal companion object {
        /** Unpacked sizes, with the archive still on disk while it unpacks. */
        private const val UBUNTU_SPACE = 150_000_000L
        private const val TOOLS_SPACE = 450_000_000L
        private const val CODE_SERVER_SPACE = 1_000_000_000L

        /** Left free for the rest of the phone. */
        private const val SPARE_SPACE = 500_000_000L
        private const val BYTES_PER_GB = 1e9

        /** The items of a Repair report. */
        private const val WHOLE = "The computer"
        private const val NETWORK_FILES = "Host name and network files"
        private const val SCRIPTS = "PocketIDE's scripts"
        private const val TOOLS = "Tools and settings"
        private const val CODE_SERVER = "code-server"
        private const val SUPPORT = "Ubuntu's support"
        private const val NOT_SET_UP = "The computer is not set up yet."
        private const val FIX_CONNECTION = "Check the connection and tap Set up again. It continues where it stopped."
        private const val FIX_TRY_AGAIN = "Tap Set up again. If it keeps stopping, reset the computer."
        private const val FIX_RESET = "Reset the computer. Your projects and sign-ins are kept."
        private const val FIX_NEXT_LTS =
            "Update PocketIDE: once an update brings the next Ubuntu, Reset the computer to move to it. Your projects and sign-ins are kept."
        private const val FIX_SET_UP_AGAIN = "Tap Set up. Your projects and sign-ins are kept."
    }
}
