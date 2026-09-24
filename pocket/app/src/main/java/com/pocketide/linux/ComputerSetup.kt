package com.pocketide.linux

import com.pocketide.core.Clock
import com.pocketide.model.Decision
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

    /** The data rules for a big transfer: [kind] is "setup" or "update". */
    fun allow(bytes: Long, kind: String): Decision

    /** Bytes that crossed the network, for the metered-data count. */
    fun transferred(bytes: Long, kind: String)

    fun freeBytes(): Long
}

/** Where set-up keeps things: the rootfs, the one being unpacked beside it, downloads, and its record. */
internal class SetupPlaces(val rootfs: File, val downloads: File, val record: File) {
    val staging = File(rootfs.parentFile, rootfs.name + ".partial")
}

/** An expected stop, already in the owner's words. */
internal class SetupStop(val why: String, val fix: String) : Exception(why)

/**
 * Builds, repairs, updates and removes the computer. Every step checks what is already done
 * (the [SetupRecord], the files themselves), so running it again after a kill continues where
 * it stopped, and running it on a finished computer only refreshes what the phone provides.
 *
 * Callers run one operation at a time.
 */
internal class ComputerSetup(
    private val places: SetupPlaces,
    private val host: SetupHost,
    private val fetcher: Fetcher,
    runner: GuestRunner,
    assets: LinuxAssets,
    private val clock: Clock,
    private val publish: (ComputerState) -> Unit,
    private val ubuntu: PinnedDownload = LinuxPins.ubuntuBase,
    private val ubuntuVersion: String = LinuxPins.UBUNTU_VERSION,
    private val codeServer: CodeServerPin = LinuxPins.codeServer,
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

    fun codeServerVersion(): String? = records.load().codeServer

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
     * Fix-it level 4. A computer that is not whole is set up again from where it stopped; a
     * whole one has each part checked and, where it can be, put right. Files the owner may
     * have changed are only ever written when missing.
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
            guestFiles() + listOf(tools(), securityFixes(), codeServerItem(record))
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
        val decision = host.allow(APT_BYTES, KIND_UPDATE)
        if (!decision.allowed) {
            return RepairItem(TOOLS, RepairStatus.NOTE, "Skipped, because it may download packages. ${decision.reason ?: WAITING_FOR_WIFI}")
        }
        var installed = 0
        val result = scripts.run(places.rootfs, GuestScripts.BOOTSTRAP) { line ->
            countFetched(line, KIND_UPDATE)
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

    private suspend fun securityFixes(): RepairItem {
        val decision = host.allow(UPDATE_BYTES, KIND_UPDATE)
        if (!decision.allowed) return RepairItem(FIXES, RepairStatus.NOTE, "Skipped. ${decision.reason ?: WAITING_FOR_WIFI}")
        var fixed = 0
        val result = scripts.run(places.rootfs, GuestScripts.UPDATE) { line ->
            countFetched(line, KIND_UPDATE)
            if (line is GuestLine.Fixed) fixed = line.count
        }
        return when {
            result.exitCode != 0 -> RepairItem(FIXES, RepairStatus.WARN, "Stopped: ${stoppedBecause(result)}. They are tried again every day.")
            fixed == 0 -> RepairItem(FIXES, RepairStatus.OK, "None were waiting.")
            else -> RepairItem(FIXES, RepairStatus.NEW, "Installed ${fixesText(fixed)}.")
        }
    }

    /** code-server is checked by starting it; one that does not start is unpacked again from the pinned release. */
    private suspend fun codeServerItem(record: SetupRecord): RepairItem {
        val version = record.codeServer ?: codeServer.version
        if (slot.current() == version && slot.reports(CodeServerSlot.LINK, version)) {
            return RepairItem(CODE_SERVER, RepairStatus.OK, "Version $version starts.")
        }
        val pin = codeServer
        val missing = remaining(pin.download())
        if (missing > 0) {
            val decision = host.allow(missing, KIND_UPDATE)
            if (!decision.allowed) {
                return RepairItem(CODE_SERVER, RepairStatus.WARN, "It does not start. It is downloaded again when allowed. ${decision.reason ?: WAITING_FOR_WIFI}")
            }
        }
        val archive = fetcher.fetch(pin.download(), File(places.downloads, pin.download().fileName), KIND_UPDATE) {}
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

    private fun stoppedBecause(result: ScriptResult) = result.lastWords?.removeSuffix(".") ?: "exit code ${result.exitCode}"

    /** Deletes the computer and what set-up downloaded; the record goes first, so a kill never leaves "ready". */
    suspend fun remove() = withContext(Dispatchers.IO) {
        records.clear()
        Trees.discard(places.staging)
        Trees.discard(places.rootfs)
        Trees.sweep(places.staging)
        Trees.sweep(places.rootfs)
        (Downloader.filesFor(ubuntu, places.downloads) + Downloader.filesFor(codeServer.download(), places.downloads))
            .forEach { Files.deleteIfExists(it.toPath()) }
        publish(ComputerState.NotInstalled)
    }

    /** Ubuntu's security fixes, after running a bootstrap an app update changed. */
    suspend fun updateBase(): UpdateOutcome = withContext(Dispatchers.IO) {
        val record = records.load()
        if (record.readyAt == null || !healthy(record)) return@withContext UpdateOutcome.Waiting(NOT_SET_UP)
        val decision = host.allow(UPDATE_BYTES, KIND_UPDATE)
        if (!decision.allowed) return@withContext UpdateOutcome.Waiting(decision.reason ?: WAITING_FOR_WIFI)
        publish(ComputerState.Updating("Ubuntu's security fixes"))
        try {
            refreshGuest(places.rootfs)
            val refreshed = record.bootstrap != scripts.bootstrapVersion()
            if (refreshed) {
                val result = scripts.run(places.rootfs, GuestScripts.BOOTSTRAP) { countFetched(it, KIND_UPDATE) }
                if (result.exitCode != 0) {
                    return@withContext UpdateOutcome.Failed("Refreshing Ubuntu stopped: ${result.lastWords ?: "exit code ${result.exitCode}"}")
                }
                records.save(record.copy(bootstrap = scripts.bootstrapVersion()))
            }
            var fixed = 0
            val result = scripts.run(places.rootfs, GuestScripts.UPDATE) { line ->
                countFetched(line, KIND_UPDATE)
                if (line is GuestLine.Fixed) fixed = line.count
            }
            if (result.exitCode != 0) {
                return@withContext UpdateOutcome.Failed("Ubuntu's security fixes stopped: ${result.lastWords ?: "exit code ${result.exitCode}"}")
            }
            baseOutcome(refreshed, fixed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            UpdateOutcome.Failed(broken(failure).why)
        } finally {
            publish(stateOnDisk())
        }
    }

    /**
     * Moves code-server to [pin]. [hold] stops new rooms from starting and answers false while
     * any are running; [release] opens them again.
     */
    suspend fun updateCodeServer(pin: CodeServerPin, hold: () -> Boolean, release: () -> Unit): UpdateOutcome =
        withContext(Dispatchers.IO) {
            val record = records.load()
            if (record.readyAt == null || !healthy(record)) return@withContext UpdateOutcome.Waiting(NOT_SET_UP)
            if (record.codeServer == pin.version) return@withContext UpdateOutcome.UpToDate
            val staged = slot.unpacked(pin.version)
            if (!staged) {
                val decision = host.allow(pin.bytes, KIND_UPDATE)
                if (!decision.allowed) return@withContext UpdateOutcome.Waiting(decision.reason ?: WAITING_FOR_WIFI)
                if (host.freeBytes() < pin.bytes + CODE_SERVER_SPACE) {
                    return@withContext UpdateOutcome.Failed("There is not enough free space to update code-server.")
                }
            }
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
            fetcher.fetch(pin.download(), archive, KIND_UPDATE) {}
            slot.unpack(pin.version, archive) {}
            Files.deleteIfExists(archive.toPath())
        }
        val stays = "code-server ${record.codeServer} stays."
        if (!slot.reports("/opt/${CodeServerSlot.folder(pin.version)}", pin.version)) {
            slot.delete(pin.version)
            return UpdateOutcome.Failed("code-server ${pin.version} did not start, so $stays")
        }
        // Kept unpacked: the switch is tried again when no room is open.
        if (!hold()) return UpdateOutcome.Waiting("code-server ${pin.version} is ready; it switches over when no agent is open.")
        try {
            val previous = slot.switchTo(pin.version)
            if (!slot.reports(CodeServerSlot.LINK, pin.version)) {
                previous?.let(slot::switchBack)
                slot.delete(pin.version)
                return UpdateOutcome.Failed("code-server ${pin.version} did not start after the switch, so $stays")
            }
            records.save(record.copy(codeServer = pin.version, codeServerSha256 = pin.sha256))
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
        records.save(record.copy(readyAt = clock.now()))
        publish(ComputerState.Ready)
    }

    /** The data rules and free space, checked for what this run still has to fetch and unpack. */
    private fun checkRoom(baseInPlace: Boolean, toolsDone: Boolean, codeServerDone: Boolean) {
        val download = (if (baseInPlace) 0 else remaining(ubuntu)) +
            (if (toolsDone) 0 else APT_BYTES) +
            (if (codeServerDone) 0 else remaining(codeServer.download()))
        if (download > 0) {
            val decision = host.allow(download, KIND_SETUP)
            if (!decision.allowed) throw SetupStop(decision.reason ?: WAITING_FOR_WIFI, FIX_WIFI)
        }
        val space = (if (baseInPlace) 0 else UBUNTU_SPACE) +
            (if (toolsDone) 0 else TOOLS_SPACE) +
            (if (codeServerDone) 0 else CODE_SERVER_SPACE)
        if (space > 0 && host.freeBytes() < space + SPARE_SPACE) {
            throw SetupStop("There is not enough free space for the computer.", "Free some space on the phone, then try again.")
        }
    }

    private suspend fun placeUbuntu(bar: Bar): SetupRecord {
        val step = "Downloading Ubuntu $ubuntuVersion…"
        bar.enter(Stage.DOWNLOAD_UBUNTU, step)
        val archive = fetcher.fetch(ubuntu, File(places.downloads, ubuntu.fileName), KIND_SETUP) {
            bar.downloaded(it, ubuntu.bytes)
        }
        bar.downloadFinished(ubuntu.bytes)
        bar.enter(Stage.UNPACK_UBUNTU, "Unpacking Ubuntu…")
        Trees.delete(places.staging.toPath())
        extractor.extract(archive, places.staging) { bar.within(it) }
        val guest = GuestRoot(places.staging)
        GuestConfig.writeBasics(guest, onlyMissing = false)
        GuestConfig.writeResolver(guest, host.dnsServers())
        scripts.install(places.staging)
        // proot's link2symlink writes absolute host paths into the links it makes, so the tree
        // must be in its final place before anything runs inside it; moved later, those links
        // would point at a folder that no longer exists.
        Trees.discard(places.rootfs)
        Files.move(places.staging.toPath(), places.rootfs.toPath(), StandardCopyOption.ATOMIC_MOVE)
        val record = SetupRecord(ubuntu = ubuntuVersion, base = ubuntu.sha256)
        records.save(record)
        Files.deleteIfExists(archive.toPath())
        return record
    }

    private suspend fun runBootstrap(record: SetupRecord, bar: Bar): SetupRecord {
        bar.enter(Stage.TOOLS, "Preparing Ubuntu…")
        val progress = ToolsProgress(bar)
        val result = scripts.run(places.rootfs, GuestScripts.BOOTSTRAP) { line ->
            countFetched(line, KIND_SETUP)
            progress.show(line)
        }
        if (result.exitCode != 0) {
            throw SetupStop("Setting up Ubuntu stopped: ${result.lastWords ?: "exit code ${result.exitCode}"}", FIX_TRY_AGAIN)
        }
        return record.copy(bootstrap = scripts.bootstrapVersion()).also(records::save)
    }

    private suspend fun placeCodeServer(record: SetupRecord, bar: Bar): SetupRecord {
        val pin = codeServer
        bar.enter(Stage.DOWNLOAD_CODE_SERVER, "Downloading code-server ${pin.version}…")
        val archive = fetcher.fetch(pin.download(), File(places.downloads, pin.download().fileName), KIND_SETUP) {
            bar.downloaded(it, pin.bytes)
        }
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

    private fun remaining(pin: PinnedDownload): Long =
        pin.bytes - Downloader.filesFor(pin, places.downloads).maxOf { if (it.isFile) it.length().coerceAtMost(pin.bytes) else 0 }

    private fun countFetched(line: GuestLine, kind: String) {
        if (line is GuestLine.Fetched) host.transferred(line.bytes, kind)
    }

    private fun fixesText(fixed: Int) = if (fixed == 1) "1 security fix" else "$fixed security fixes"

    private fun baseOutcome(refreshed: Boolean, fixed: Int): UpdateOutcome {
        val fixes = fixesText(fixed)
        return when {
            refreshed && fixed > 0 -> UpdateOutcome.Updated("Refreshed Ubuntu's set-up and installed $fixes.")
            refreshed -> UpdateOutcome.Updated("Refreshed Ubuntu's set-up.")
            fixed > 0 -> UpdateOutcome.Updated("Installed $fixes.")
            else -> UpdateOutcome.UpToDate
        }
    }

    /** An unexpected failure, in the owner's words. */
    private fun broken(failure: Exception): ComputerState.Broken = when {
        failure is ChecksumMismatch ->
            ComputerState.Broken("The download of ${failure.fileName} did not match its published checksum, so it was deleted.", FIX_TRY_AGAIN)
        failure is DownloadFailed -> ComputerState.Broken("${failure.message}.", FIX_CONNECTION)
        failure is UnsafeArchive -> ComputerState.Broken("A downloaded archive was not safe to unpack.", FIX_TRY_AGAIN)
        Downloader.outOfSpace(failure) -> ComputerState.Broken("The phone ran out of space.", "Free some space on the phone, then try again.")
        failure is FileSystemException -> ComputerState.Broken("Set-up could not write its files.", FIX_RESET)
        failure is IOException -> ComputerState.Broken("Set-up stopped: ${failure.message ?: "a file could not be read or written"}.", FIX_TRY_AGAIN)
        else -> ComputerState.Broken("Set-up stopped unexpectedly.", FIX_RESET)
    }

    private enum class Stage(val weight: Int) {
        DOWNLOAD_UBUNTU(6), UNPACK_UBUNTU(8), TOOLS(44), DOWNLOAD_CODE_SERVER(22), UNPACK_CODE_SERVER(16), CHECK(4),
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
                        phase < 30 -> 10 + 20 * part
                        !line.installing -> 30 + 25 * part
                        else -> 55 + 35 * part
                    }
                    bar.step("$label: ${line.text}")
                    bar.within(position / 100f)
                }
                is GuestLine.Text -> if (line.text.endsWith("…")) {
                    label = line.text.removeSuffix("…")
                    bar.step(line.text)
                }
                is GuestLine.Fetched, is GuestLine.Fixed, is GuestLine.Installed -> Unit
            }
        }
    }

    private companion object {
        const val KIND_SETUP = "setup"
        const val KIND_UPDATE = "update"

        /** Measured on 24 Sep 2026: 27.9 MB of package lists and 37.4 MB of packages. */
        const val APT_BYTES = 70_000_000L

        /** The package lists a daily update fetches, and a typical day's fixes. */
        const val UPDATE_BYTES = 40_000_000L

        /** Unpacked sizes, with the archive still on disk while it unpacks. */
        const val UBUNTU_SPACE = 150_000_000L
        const val TOOLS_SPACE = 300_000_000L
        const val CODE_SERVER_SPACE = 1_000_000_000L

        /** Left free for the rest of the phone. */
        const val SPARE_SPACE = 500_000_000L

        const val WAITING_FOR_WIFI = "Waiting for Wi-Fi…"

        /** The items of a Repair report. */
        const val WHOLE = "The computer"
        const val NETWORK_FILES = "Host name and network files"
        const val SCRIPTS = "PocketIDE's scripts"
        const val TOOLS = "Tools and settings"
        const val FIXES = "Ubuntu's security fixes"
        const val CODE_SERVER = "code-server"
        const val NOT_SET_UP = "The computer is not set up yet."
        const val FIX_WIFI = "Connect to Wi-Fi and tap Set up. It continues where it stopped."
        const val FIX_CONNECTION = "Check the connection and try again. It continues where it stopped."
        const val FIX_TRY_AGAIN = "Try again. If it keeps stopping, reset the computer."
        const val FIX_RESET = "Reset the computer. Projects and chats are not affected."
        const val FIX_SET_UP_AGAIN = "Set it up again. Projects and chats are not affected."
    }
}
