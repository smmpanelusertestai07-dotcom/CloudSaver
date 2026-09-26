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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
import java.nio.file.attribute.PosixFilePermissions
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The computer on the phone: Ubuntu under proot, rooted at [AppDirs.rootfs].
 *
 * Set-up, reset, repair and updates run one at a time in the computer's own scope, so leaving
 * a screen never stops dpkg half way; the caller only stops waiting. Every program starts
 * through [ProotCommand] with an empty environment and only the folders its command names.
 */
internal class ProotComputer(
    private val context: Context,
    private val dirs: AppDirs,
    private val dataBudget: () -> DataBudget,
    private val clock: Clock,
    /** The computer has just become ready after a set-up, a reset or a repair: the agents go in next. */
    private val onReady: () -> Unit = {},
) : Computer {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val oneAtATime = Mutex()
    private val host = ProotHost(File(context.applicationInfo.nativeLibraryDir), dirs.prootTmp)
    private val variablesDir = File(dirs.prootTmp, VARIABLES_DIR)
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
        // Variables files a killed app could not delete.
        variablesDir.listFiles()?.forEach { it.delete() }
        // A long set-up or a day of work crosses from Wi-Fi to mobile data; Linux follows.
        dns.watch { servers -> runCatching { GuestConfig.writeResolver(GuestRoot(dirs.rootfs), servers) } }
    }

    override suspend fun install() {
        exclusively { setup.install() }
        announceIfReady()
    }

    override suspend fun reset() {
        exclusively {
            close("The computer is being rebuilt.")
            try {
                processes.stopAllAndWait()
                mutableState.value = ComputerState.Installing("Removing the old computer…", null, 0, 0)
                if (removeQuietly() == null) setup.install()
            } finally {
                reopen()
            }
        }
        announceIfReady()
    }

    override suspend fun remove() {
        exclusively {
            close("The computer is being removed.")
            try {
                processes.stopAllAndWait()
                removeQuietly()
            } finally {
                reopen()
            }
        }
    }

    override suspend fun restart() = processes.stopAllAndWait()

    override suspend fun repair(): List<RepairItem> = exclusively {
        hostItems() + setup.repair()
    }.also { announceIfReady() }

    private fun announceIfReady() {
        if (state.value == ComputerState.Ready) runCatching(onReady)
    }

    override fun start(command: LinuxCommand): Process = gate.read {
        closedBecause?.let { throw IllegalStateException(it) }
        val current = state.value
        check(current is ComputerState.Ready || current is ComputerState.Updating) { "The computer is not set up yet." }
        val guest = GuestRoot(dirs.rootfs)
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

    override fun liveProcesses(process: Process): Int = processes.count(process)

    override suspend fun checkNetwork(): NetworkReport = withContext(Dispatchers.IO) {
        val hosts = HostProbe(Http.client).checkAll(NeededHosts.all)
        val linux = linuxLookup()
        NetworkReport(
            checkedAt = clock.now(),
            hosts = hosts,
            linuxDns = linux,
            blockers = NetworkBlockers.describe(PhoneNetwork.read(context), hosts, linux),
        )
    }

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
        val variables = command.env.takeIf { it.isNotEmpty() }?.let { writeVariables(command) }
        try {
            // Set-up and other commands without a room share the computer's own /tmp as their /dev/shm.
            val shm = File(root, "tmp").takeIf { it.isDirectory }
            val call = ProotCommand.build(host, root, standIns.binds(), TimeZone.getDefault().id, command, variables, shm)
            val builder = ProcessBuilder(call.argv).redirectErrorStream(command.mergeErrors)
            builder.environment().run {
                clear()
                putAll(call.environment)
            }
            return builder.start().also(processes::track)
        } finally {
            // launch.pl deletes the file once it has read it; this catches a start that failed first.
            variables?.let { file -> work.launch { delay(VARIABLES_LIFETIME_MS); file.delete() } }
        }
    }

    /** The command's variables, in a file only the app can read, under a name no one can guess. */
    private fun writeVariables(command: LinuxCommand): File {
        Files.createDirectories(variablesDir.toPath())
        val file = File(variablesDir, UUID.randomUUID().toString())
        val path = Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.write(path, ProotCommand.variables(command))
        return file
    }

    private suspend fun removeQuietly(): ComputerState.Broken? = try {
        setup.remove()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        ComputerState.Broken("The old computer could not be deleted completely.", "Restart the phone, then reset the computer again.")
            .also { mutableState.value = it }
    }

    /** The parts of a repair that live outside Linux. */
    private fun hostItems(): List<RepairItem> = buildList {
        add(
            if (host.proot.isFile && host.loader.isFile) {
                RepairItem("proot", RepairStatus.OK, "Present in the app.")
            } else {
                RepairItem("proot", RepairStatus.WARN, "Missing from the app. Install PocketIDE again from its release page.")
            },
        )
        val free = dirs.base.usableSpace
        add(
            if (free >= LOW_SPACE) {
                RepairItem("Free space", RepairStatus.OK, "${free / 1_000_000_000} GB free.")
            } else {
                RepairItem("Free space", RepairStatus.WARN, "Only ${free / 1_000_000} MB free. Free some space on the phone.")
            },
        )
        val standing = runCatching { standIns.binds().keys }.getOrDefault(emptySet())
        if (standing.isNotEmpty()) {
            add(RepairItem("Hidden system files", RepairStatus.NOTE, "Android hides ${standing.joinToString()}; Linux sees placeholders."))
        }
    }

    /** Looks up a name from inside Linux, the way apt and the agents do. */
    private suspend fun linuxLookup(): HostCheck? {
        if (state.value !is ComputerState.Ready) return null
        val name = NeededHosts.LINUX_LOOKUP
        val purpose = "Looking up names inside Linux"
        val code = try {
            withTimeout(LOOKUP_TIMEOUT_MS) { run(LinuxCommand(listOf("getent", "ahosts", name))) {} }
        } catch (tooSlow: TimeoutCancellationException) {
            return HostCheck(name, purpose, ok = false, detail = "No answer within ${LOOKUP_TIMEOUT_MS / 1000} s.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return HostCheck(name, purpose, ok = false, detail = "Linux could not be started: ${failure.message}")
        }
        return if (code == 0) {
            HostCheck(name, purpose, ok = true, detail = "Linux looked up $name.")
        } else {
            HostCheck(name, purpose, ok = false, detail = "Linux could not look up $name.")
        }
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
                    if (first == null) first = GuestFacts.version(line)
                }
            }
        } catch (tooSlow: TimeoutCancellationException) {
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            null
        }
        val shown = first.takeIf { code == 0 } ?: AGY_DOES_NOT_START
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
        const val VARIABLES_DIR = "variables"
        const val VARIABLES_LIFETIME_MS = 60_000L
        const val LOOKUP_TIMEOUT_MS = 15_000L
        const val LOW_SPACE = 2_000_000_000L

        /** The Antigravity agent's id (AgentInfo.id), and where its CLI installs itself. */
        const val ANTIGRAVITY_ROOM = "antigravity"
        const val AGY_PATH = ".gemini/bin/agy"
        const val AGY_TIMEOUT_MS = 30_000L
        const val AGY_DOES_NOT_START = "installed, but it does not start on this phone"

        fun readHostFile(path: String): String? = runCatching { File(path).readText() }.getOrNull()
    }
}
