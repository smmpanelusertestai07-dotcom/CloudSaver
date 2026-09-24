package com.pocketide.linux

import android.content.Context
import android.os.Build
import com.pocketide.core.AppDirs
import com.pocketide.core.Clock
import com.pocketide.core.Http
import com.pocketide.model.Decision
import com.pocketide.sync.DataBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.TimeZone
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The computer on the phone: Ubuntu under proot, rooted at [AppDirs.rootfs].
 *
 * Set-up, reset and updates run one at a time in the computer's own scope, so leaving a screen
 * never stops dpkg half way; the caller only stops waiting. Every program starts through
 * [ProotCommand] with an empty environment and only the folders its command names.
 */
internal class ProotComputer(
    context: Context,
    private val dirs: AppDirs,
    private val dataBudget: () -> DataBudget,
    private val clock: Clock,
) : Computer {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val oneAtATime = Mutex()
    private val host = ProotHost(File(context.applicationInfo.nativeLibraryDir), dirs.prootTmp)
    private val table = ProcTable()
    private val processes = ProcessKeeper(
        signals = { pid, signal -> android.os.Process.sendSignal(pid, signal) },
        table = table,
        scope = work,
    )
    private val dns = PhoneDns(context)
    private val cores = CpuFacts.cores(readHostFile("/sys/devices/system/cpu/possible"))
        ?: Runtime.getRuntime().availableProcessors()
    private val standIns = ProcStandIns(dirs.procFakes, cores)

    /** New programs may not start while this holds a reason (a rebuild, or a code-server switch). */
    private val gate = ReentrantReadWriteLock()
    private var closedBecause: String? = null

    private val phone = object : SetupHost {
        override fun dnsServers(): List<InetAddress> = dns.servers()
        override fun allow(bytes: Long, kind: String): Decision = dataBudget().allow(bytes, kind, big = true)
        override fun transferred(bytes: Long, kind: String) = dataBudget().record(bytes, kind)
        override fun freeBytes(): Long = dirs.base.usableSpace
    }

    private val mutableState = MutableStateFlow<ComputerState>(ComputerState.NotInstalled)
    override val state: StateFlow<ComputerState> = mutableState.asStateFlow()

    private val setup = ComputerSetup(
        places = SetupPlaces(rootfs = dirs.rootfs, downloads = dirs.downloads, record = File(dirs.base, RECORD_FILE)),
        host = phone,
        fetcher = Downloader(Http.downloads, onTransferred = phone::transferred),
        runner = { root, argv, onLine -> drain(launch(root, LinuxCommand(argv)), true, onLine, processes::stop) },
        assets = AndroidLinuxAssets(context.assets),
        clock = clock,
        publish = { mutableState.value = it },
    )

    private val sizes = SizeCache()

    @Volatile
    private var agyVersion: Pair<String, String>? = null

    init {
        mutableState.value = setup.stateOnDisk()
    }

    override suspend fun install() = exclusively { setup.install() }

    override suspend fun reset() = exclusively {
        close("The computer is being rebuilt.")
        try {
            processes.stopAll()
            mutableState.value = ComputerState.Installing("Removing the old computer…", null, 0, 0)
            setup.remove()
            setup.install()
        } finally {
            reopen()
        }
    }

    override suspend fun remove() = exclusively {
        close("The computer is being removed.")
        try {
            processes.stopAll()
            setup.remove()
        } finally {
            reopen()
        }
    }

    override fun start(command: LinuxCommand): Process = gate.read {
        closedBecause?.let { throw IllegalStateException(it) }
        val current = state.value
        check(current is ComputerState.Ready || current is ComputerState.Updating) { "The computer is not set up yet." }
        val guest = GuestRoot(dirs.rootfs)
        dns.watch { servers -> runCatching { GuestConfig.writeResolver(guest, servers) } }
        runCatching { GuestConfig.writeBasics(guest, onlyMissing = true) }
        launch(dirs.rootfs, command)
    }

    override suspend fun run(command: LinuxCommand, onLine: (String) -> Unit): Int {
        val process = withContext(Dispatchers.IO) { start(command) }
        return drain(process, command.mergeErrors, onLine, processes::stop)
    }

    override fun stop(process: Process) = processes.stop(process)

    override suspend fun updateBase(): UpdateOutcome = exclusively { setup.updateBase() }

    override suspend fun updateCodeServer(pin: CodeServerPin): UpdateOutcome = exclusively {
        setup.updateCodeServer(pin, hold = ::closeForSwitch, release = ::reopen)
    }

    override fun liveProcesses(): Int = table.countOwnedBy(android.os.Process.myUid(), android.os.Process.myPid())

    override suspend fun info(): ComputerInfo = withContext(Dispatchers.IO) {
        val guest = GuestRoot(dirs.rootfs)
        val installed = state.value.let { it is ComputerState.Ready || it is ComputerState.Updating }
        ComputerInfo(
            cpu = CpuFacts.describe(readHostFile("/proc/cpuinfo"), socName()),
            cores = cores,
            kernel = System.getProperty("os.version").orEmpty().ifBlank { "unknown" },
            android = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            ubuntu = GuestFacts.prettyName(guest.readText("/etc/os-release")),
            codeServer = GuestFacts.packageVersion(guest.readText("${CodeServerSlot.LINK}/package.json")),
            agy = if (installed) agyVersion() else null,
            rootfsBytes = sizeBytes(),
            vaBits = KernelFacts.vaBits(readHostFile("/proc/self/maps")),
        )
    }

    override fun sizeBytes(): Long = sizes.get(clock.now()) { Trees.bytes(dirs.rootfs.toPath()) }

    /** Starts proot on [root]; the programs of set-up use the rootfs being built. */
    private fun launch(root: File, command: LinuxCommand): Process {
        for (bind in command.binds) {
            if (!File(bind.hostPath).exists()) throw IOException("A folder this command needs is missing: ${bind.guestPath}")
        }
        Files.createDirectories(host.tmpDir.toPath())
        // Android gives a container no resolver; the phone's servers of the moment are written each time.
        runCatching { GuestConfig.writeResolver(GuestRoot(root), dns.servers()) }
        val call = ProotCommand.build(host, root, standIns.binds(), TimeZone.getDefault().id, command)
        val builder = ProcessBuilder(call.argv).redirectErrorStream(command.mergeErrors)
        builder.environment().run {
            clear()
            putAll(call.environment)
        }
        return builder.start().also(processes::track)
    }

    /**
     * Antigravity's CLI lives in its room's home and says its own version. Asked once per
     * binary: an update replaces the file, which changes its size or time.
     */
    private suspend fun agyVersion(): String? {
        val home = dirs.roomHome(ANTIGRAVITY_ROOM)
        val binary = File(home, AGY_PATH)
        val attributes = runCatching {
            Files.readAttributes(binary.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return null
        val key = "${attributes.size()}:${attributes.lastModifiedTime().toMillis()}"
        agyVersion?.takeIf { it.first == key }?.let { return it.second }
        var first: String? = null
        val code = try {
            withTimeout(AGY_TIMEOUT_MS) {
                run(LinuxCommand(listOf("/root/$AGY_PATH", "--version"), binds = listOf(Bind(home.absolutePath, "/root")))) { line ->
                    if (first == null && line.isNotBlank()) first = line
                }
            }
        } catch (tooSlow: TimeoutCancellationException) {
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            null
        }
        val shown = GuestFacts.version(first).takeIf { code == 0 } ?: AGY_DOES_NOT_START
        agyVersion = key to shown
        return shown
    }

    private fun socName(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
            .filter { it.isNotBlank() && it != Build.UNKNOWN }
            .joinToString(" ")
            .ifBlank { null }
    }

    /** Runs [block] after any other set-up, reset or update, in the computer's own scope. */
    private suspend fun <T> exclusively(block: suspend () -> T): T {
        val result = work.async { oneAtATime.withLock { block() } }.await()
        sizes.forget()
        return result
    }

    private fun close(reason: String) = gate.write { closedBecause = reason }

    private fun reopen() = gate.write { closedBecause = null }

    /** Closes the computer to new programs for a switch, but only when none is running. */
    private fun closeForSwitch(): Boolean = gate.write {
        if (processes.running() > 0) {
            false
        } else {
            closedBecause = "code-server is being updated. Try again in a minute."
            true
        }
    }

    /** The rootfs size walks every file, so it is measured at most once a minute. */
    private class SizeCache {
        private var measuredAt = 0L
        private var bytes = -1L

        @Synchronized
        fun get(now: Long, measure: () -> Long): Long {
            if (bytes >= 0 && now - measuredAt in 0 until FRESH_MS) return bytes
            bytes = measure()
            measuredAt = now
            return bytes
        }

        @Synchronized
        fun forget() {
            bytes = -1
        }

        private companion object {
            const val FRESH_MS = 60_000L
        }
    }

    private companion object {
        const val RECORD_FILE = "computer.json"

        /** The Antigravity agent's id (AgentInfo.id), and where its CLI installs itself. */
        const val ANTIGRAVITY_ROOM = "antigravity"
        const val AGY_PATH = ".gemini/bin/agy"
        const val AGY_TIMEOUT_MS = 30_000L
        const val AGY_DOES_NOT_START = "installed, but it does not start on this phone"

        fun readHostFile(path: String): String? = runCatching { File(path).readText() }.getOrNull()
    }
}
