package com.pocketide.agents

import com.pocketide.linux.Trees
import com.pocketide.model.AgentCandidate
import com.pocketide.model.AgentInfo
import com.pocketide.model.AgentSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The agents on this phone: the official three, built in, and the ones the owner added from
 * discovery. Installs and updates run one at a time; an update that fails the doctor is rolled
 * back to the version before it, whose package is kept for that.
 */
internal class OpenVsxCatalog(
    private val env: AgentsEnv,
    private val vsx: OpenVsx,
    private val extensions: ExtensionInstaller,
    private val agy: AgyInstaller,
    private val doctor: AgentDoctor,
    private val discovery: Discovery,
    private val store: AgentStore,
    /** One folder per agent, holding its current package and the one before. */
    private val packages: File,
) : AgentCatalog {

    private val state = MutableStateFlow(store.read())
    private val stateLock = Mutex()
    private val installLock = Mutex()

    override val installed: StateFlow<List<AgentInfo>> = combine(state, env.onlyOfficial, ::visible)
        .stateIn(env.scope, SharingStarted.Eagerly, visible(state.value, env.onlyOfficial.value))

    override val candidates: StateFlow<List<AgentCandidate>> = combine(state, env.onlyOfficial, ::waiting)
        .stateIn(env.scope, SharingStarted.Eagerly, waiting(state.value, env.onlyOfficial.value))

    override fun find(agentId: String): AgentInfo? {
        val current = state.value
        val agent = OfficialAgents.find(agentId) ?: current.added.firstOrNull { it.id == agentId } ?: return null
        return withVersion(agent, current)
    }

    override fun facts(extensionId: String): CandidateFacts? =
        state.value.facts.firstOrNull { it.extensionId.equals(extensionId, ignoreCase = true) }

    override suspend fun discover() {
        if (env.onlyOfficial.value) return
        val known = state.value.added.mapNotNull { it.extensionId?.lowercase() }.toSet()
        val found = withContext(Dispatchers.IO) { discovery.run(known) }.filterIsInstance<Verdict.Offered>().map { it.found }
        var fresh: List<AgentCandidate> = emptyList()
        change { current ->
            fresh = found.map { it.candidate }.filter { it.extensionId !in current.announced }
            current.copy(
                candidates = found.map { it.candidate },
                facts = found.map { it.facts },
                announced = (current.announced + fresh.map { it.extensionId }).distinct(),
                discoveredAt = env.clock.now(),
            )
        }
        if (fresh.isNotEmpty()) env.announce(fresh)
    }

    override suspend fun add(candidate: AgentCandidate): DoctorReport = installLock.withLock {
        check(!env.onlyOfficial.value) { "Turn off \"Only official agents\" to add other agents." }
        val agent = agentFrom(withContext(Dispatchers.IO) { recheck(candidate) })
        check(find(agent.id) == null) { "${agent.displayName} is already on this phone." }
        change { it.copy(added = it.added + agent) }
        var kept = false
        try {
            val report = bringUpToDate(agent) ?: doctor.check(agent)
            if (report.ok) {
                kept = true
                change { it.copy(candidates = it.candidates.filterNot { c -> c.extensionId == candidate.extensionId }) }
                report
            } else {
                report.copy(note = listOfNotNull(report.note, "${agent.displayName} was not added.").joinToString(" "))
            }
        } finally {
            if (!kept) forget(agent.id)
        }
    }

    override suspend fun remove(agentId: String) {
        check(OfficialAgents.find(agentId) == null) { "The official agents stay. \"Only official agents\" hides the others instead." }
        installLock.withLock {
            // Deleting the room deletes its sessions' worktrees and chats too: save them first.
            val unsaved = env.saveBeforeRemoving(agentId)
            check(unsaved.isEmpty()) { AgentRemoval.refusal(find(agentId)?.displayName ?: agentId, unsaved) }
            forget(agentId)
        }
    }

    /**
     * An agent that is already installed stays usable when its update cannot be checked or
     * fetched now (offline, waiting for Wi-Fi): only a missing agent, or a rejected update, is
     * an error here. [updateAll] reports every problem.
     */
    override suspend fun ensureInstalled(agentId: String) {
        val agent = usable(agentId)
        try {
            update(agent)
        } catch (notNow: IOException) {
            if (notNow is PackageRejected || !present(agent)) throw notNow
        }
    }

    override suspend fun doctor(agentId: String): DoctorReport = withContext(Dispatchers.IO) { doctor.check(usable(agentId)) }

    override suspend fun updateAll() {
        val failures = mutableListOf<Exception>()
        for (agent in installed.value) {
            try {
                update(agent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: IOException) {
                failures += failed
            } catch (failed: IllegalStateException) {
                failures += failed
            }
        }
        // Every agent had its turn; the first problem is the one reported.
        failures.firstOrNull()?.let { throw it }
    }

    private suspend fun update(agent: AgentInfo) = installLock.withLock {
        val report = bringUpToDate(agent)
        if (report != null && !report.ok) throw PackageRejected(report.note ?: "${agent.displayName} did not pass its test on this phone.")
    }

    private suspend fun present(agent: AgentInfo): Boolean {
        val extensionId = agent.extensionId ?: return agyPresent(agent.id)
        return doctor.installed(agent.id, extensionId) != null
    }

    private suspend fun agyPresent(agentId: String): Boolean =
        withContext(Dispatchers.IO) { File(env.dirs.roomHome(agentId), RoomPaths.AGY_IN_HOME).isFile }

    private fun usable(agentId: String): AgentInfo {
        val agent = find(agentId) ?: throw IllegalStateException("PocketIDE does not know the agent \"$agentId\".")
        check(agent.official || !env.onlyOfficial.value) { "\"Only official agents\" is on, so ${agent.displayName} is not used." }
        return agent
    }

    /**
     * Installs [agent] when it is missing, or moves it to a newer version; null when nothing
     * changed. A failed update puts the version before it back and throws; a failed first
     * install is removed again and its report returned.
     */
    private suspend fun bringUpToDate(agent: AgentInfo): DoctorReport? = withContext(Dispatchers.IO) {
        env.computerProblem()?.let { throw IllegalStateException(it) }
        when (agent.surface) {
            AgentSurface.NATIVE_HUB -> bringAgyUpToDate(agent)
            AgentSurface.CODE_SERVER_EXTENSION -> bringExtensionUpToDate(agent)
        }
    }

    private suspend fun bringExtensionUpToDate(agent: AgentInfo): DoctorReport? {
        val extensionId = agent.extensionId ?: throw IllegalStateException("${agent.displayName} has no extension to install.")
        val namespace = OfficialAgents.namespaces[agent.id] ?: extensionId.substringBefore('.')
        val vscode = env.vscodeVersion() ?: throw IllegalStateException(NO_CODE_SERVER)
        val present = doctor.installed(agent.id, extensionId)
        val release = extensions.newest(extensionId, namespace, vscode, skip = state.value.rejectedVersions(agent.id))
        if (present != null && !newer(release.version, present.version)) return null
        // Files are not swapped under a running room; the next check updates it.
        if (present != null && env.roomInUse(agent.id)) return null

        val folder = packageFolder(agent.id)
        val fetched = extensions.fetch(release, folder)
        extensions.install(agent.id, extensionId, fetched.file)
        val report = doctor.check(agent, release.version)
        if (report.ok) {
            record(agent.id, release.version, fetched.sha256, report.openCommand)
            keepOnly(folder, setOfNotNull(ExtensionInstaller.packageFile(folder, release.version), present?.let { ExtensionInstaller.packageFile(folder, it.version) }))
            configure(agent.id)
            return report
        }

        // A version that cannot work here is not fetched again every day; the next one is.
        if (report.versionFailed) change { it.withRejected(agent.id, release.version) }
        withContext(Dispatchers.IO) { Files.deleteIfExists(fetched.file.toPath()) }
        if (present == null) {
            extensions.uninstall(agent.id, extensionId)
            return report
        }
        val restored = putBack(agent, extensionId, namespace, vscode, present.version, folder)
        configure(agent.id)
        val after = if (restored) "Version ${present.version} is back in place." else "Version ${present.version} could not be put back; repair it from the Computer screen."
        throw PackageRejected("${agent.displayName} ${release.version} did not pass its test on this phone (${report.note}). $after")
    }

    /** Installs [version] again from its kept package, or fetched afresh when none was kept. */
    private suspend fun putBack(agent: AgentInfo, extensionId: String, namespace: String, vscode: SemVer, version: String, folder: File): Boolean {
        val kept = ExtensionInstaller.packageFile(folder, version).takeIf { it.isFile }
        return try {
            val vsix = kept ?: extensions.fetch(extensions.exact(extensionId, namespace, vscode, version), folder).file
            extensions.install(agent.id, extensionId, vsix)
            doctor.installed(agent.id, extensionId)?.version == version
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: IOException) {
            false
        }
    }

    private suspend fun bringAgyUpToDate(agent: AgentInfo): DoctorReport? {
        val manifest = agy.latest()
        val record = state.value.install(agent.id)
        val present = agyPresent(agent.id)
        if (present && record != null && !newer(manifest.version, record.version)) return null
        if (present && env.roomInUse(agent.id)) return null

        val folder = packageFolder(agent.id)
        val archive = agy.fetch(manifest, folder)
        agy.install(agent.id, archive)
        val report = doctor.check(agent)
        if (report.ok) {
            agy.keep(agent.id)
            val sha256 = manifest.sha256 ?: withContext(Dispatchers.IO) { VerifiedDownload.sha256(archive) }
            record(agent.id, manifest.version, sha256)
            keepOnly(folder, setOfNotNull(archive, record?.let { AgyInstaller.archiveFile(folder, it.version) }))
            configure(agent.id)
            return report
        }
        agy.rollBack(agent.id)
        withContext(Dispatchers.IO) { Files.deleteIfExists(archive.toPath()) }
        if (!present) return report
        val before = record?.version?.let { "Version $it" } ?: "The version before it"
        throw PackageRejected("Antigravity's agy ${manifest.version} did not pass its test on this phone (${report.note}). $before is back in place.")
    }

    /** Runs every discovery check again on the live registry: a candidate may have changed since it was found. */
    private suspend fun recheck(candidate: AgentCandidate): Found {
        val namespace = candidate.extensionId.substringBefore('.')
        val name = candidate.extensionId.substringAfter('.')
        val overview = vsx.extension(namespace, name) ?: throw IOException("${candidate.displayName} is no longer on Open VSX.")
        val entry = SearchEntry(
            namespace = overview.namespace,
            name = overview.name,
            version = overview.version,
            verified = overview.verified,
            downloadCount = overview.downloadCount,
            displayName = overview.displayName,
            description = overview.description,
            deprecated = overview.deprecated,
        )
        val known = state.value.added.mapNotNull { it.extensionId?.lowercase() }.toSet()
        return when (val verdict = discovery.examine(entry, known)) {
            is Verdict.Offered -> verdict.found
            is Verdict.Skipped -> throw IllegalStateException("${candidate.displayName} can no longer be added: ${verdict.rule.why}.")
        }
    }

    private fun agentFrom(found: Found): AgentInfo {
        val candidate = found.candidate
        val name = candidate.displayName
        val publisher = candidate.publisher
        return AgentInfo(
            id = found.facts.extensionId,
            displayName = name,
            publisher = publisher,
            extensionId = found.facts.extensionId,
            surface = AgentSurface.CODE_SERVER_EXTENSION,
            official = false,
            verifiedPublisher = true,
            dataGoesTo = "Your prompts, and the code and files $name reads, go to $publisher and to the AI model service it uses.",
            signIn = "Sign in inside $name's own screen, the way $publisher describes.",
            instructionsFile = "",
            openCommand = found.facts.openCommand,
            downloads = candidate.downloads,
            communityNote = "$publisher may not be the company that makes the AI model. Your code goes to them and to the model service they use.",
        )
    }

    /** Deletes the agent's room, its kept packages and its record. */
    /** Once the room starts going, the agent goes too: a cancelled caller never leaves one without the other. */
    private suspend fun forget(agentId: String) = withContext(NonCancellable) {
        env.deleteRoom(agentId)
        withContext(Dispatchers.IO) { Trees.delete(File(packages, agentId).toPath()) }
        change { it.withoutAgent(agentId) }
    }

    private suspend fun record(agentId: String, version: String, sha256: String, openCommand: String? = null) =
        change { it.withInstall(InstallRecord(agentId, version, sha256, env.clock.now(), openCommand)) }

    /** The room's settings and companion are written again: code-server rewrote its extension list. */
    private suspend fun configure(agentId: String) {
        try {
            env.configureRoom(agentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: IOException) {
            // The room configures itself again when it next starts.
        } catch (failed: IllegalStateException) {
            // Same: the next start writes the configuration.
        }
    }

    private suspend fun packageFolder(agentId: String): File = withContext(Dispatchers.IO) {
        File(packages, agentId).also { Files.createDirectories(it.toPath()) }
    }

    private suspend fun keepOnly(folder: File, keep: Set<File>) = withContext(Dispatchers.IO) {
        folder.listFiles()?.filter { it !in keep }?.forEach { Trees.delete(it.toPath()) }
    }

    private suspend fun change(transform: (AgentsState) -> AgentsState) = stateLock.withLock {
        val next = transform(state.value)
        withContext(Dispatchers.IO) { store.write(next) }
        state.value = next
    }

    private fun visible(current: AgentsState, onlyOfficial: Boolean): List<AgentInfo> =
        (OfficialAgents.all + if (onlyOfficial) emptyList() else current.added).map { withVersion(it, current) }

    private fun waiting(current: AgentsState, onlyOfficial: Boolean): List<AgentCandidate> =
        if (onlyOfficial) emptyList() else current.candidates

    /** The agent as installed: its version, and the command that opens that version. */
    private fun withVersion(agent: AgentInfo, current: AgentsState): AgentInfo =
        current.install(agent.id)?.let { agent.copy(version = it.version, openCommand = it.openCommand ?: agent.openCommand) } ?: agent

    private fun newer(candidate: String, installed: String): Boolean {
        val next = SemVer.parse(candidate) ?: return false
        val now = SemVer.parse(installed) ?: return true
        return next > now
    }

    companion object {
        const val NO_CODE_SERVER = "The computer's code-server is missing. Repair the computer from the Computer screen."
    }
}
