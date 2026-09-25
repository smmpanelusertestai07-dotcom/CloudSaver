package com.pocketide.projects

import com.pocketide.core.AppDirs
import com.pocketide.core.Clock
import com.pocketide.github.NotConnectedException
import com.pocketide.github.RepoInfo
import com.pocketide.model.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The projects on this phone, kept in `vault/projects.json`. GitHub holds the code: the phone
 * keeps a bare clone per project, made the first time the project is opened.
 *
 * The owner's answer to "Is this your code?" is kept on the project itself ([Project.trust]), so
 * it syncs to a new phone; `vault/project-trust.json` also holds it, with the automatic answer
 * for projects never asked about.
 */
internal class ProjectRegistry(
    private val env: ProjectEnv,
    private val dirs: AppDirs,
    file: JsonFile<List<Project>>,
    trustFile: JsonFile<Map<String, ProjectTrust>>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : Projects {

    private val state = JsonState(file, emptyList(), io, normalize = ::withCloneState)
    private val trustState = JsonState(trustFile, emptyMap(), io)
    private val cloneLocks = ConcurrentHashMap<String, Mutex>()

    override val all: StateFlow<List<Project>> = state.flow

    override val trust: StateFlow<Map<String, ProjectTrust>> = trustState.flow

    init {
        scope.launch {
            quietly {
                val projects = state.current()
                trustState.current()
                trustState.update { map -> (map + answers(projects)) to Unit }
            }
        }
    }

    override suspend fun create(name: String, description: String): Project {
        // GitHub itself turns spaces into hyphens; doing it here shows the real name at once.
        val repoName = name.trim().replace(WHITESPACE, "-")
        if (!REPO_NAME.matches(repoName) || repoName == "." || repoName == "..") {
            throw ProjectException("Use letters, numbers, dots, hyphens or underscores for the name, up to 100 characters.")
        }
        val about = description.trim().replace(WHITESPACE, " ").take(MAX_DESCRIPTION)
        // With a first commit the repository has a default branch, which every session starts from.
        val project = add(network { env.gitHub.createPrivateRepo(repoName, about, autoInit = true) })
        setTrust(project.id, ProjectTrust.YOURS)
        return all.value.find { it.id == project.id } ?: project
    }

    override suspend fun import(owner: String, repo: String): Project {
        val address = RepoAddress.parse(if (repo.isBlank()) owner else "${owner.trim()}/${repo.trim()}")
            ?: throw ProjectException("Check the repository: use owner/name, or paste its GitHub address.")
        val info = network { env.gitHub.repo(address.owner, address.repo) }
            ?: throw RepoNotReachableException(env.gitHubAuth.installUrl(), address)
        val project = add(info)
        // A fork started as someone else's code, even under the owner's own account (A13).
        if (info.fork && project.trust == null) setTrust(project.id, ProjectTrust.SOMEONE_ELSES)
        trustState.update { map -> (if (project.id in map) map else map + (project.id to automaticTrust(project))) to Unit }
        return all.value.find { it.id == project.id } ?: project
    }

    override fun trustOf(projectId: String): ProjectTrust {
        val project = all.value.find { it.id == projectId }
        return project?.let(::answer) ?: trust.value[projectId] ?: project?.let(::automaticTrust) ?: ProjectTrust.SOMEONE_ELSES
    }

    override suspend fun setTrust(projectId: String, trust: ProjectTrust) {
        state.update { list -> list.map { if (it.id == projectId) it.copy(trust = trust.name) else it } to Unit }
        trustState.update { it + (projectId to trust) to Unit }
    }

    override suspend fun ensureCloned(projectId: String) = withContext(io) {
        val project = find(projectId)
        if (isCloned(project.id)) {
            try {
                fetchNow(project)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (offline: Exception) {
                // The clone on the phone is used as it is; agents keep working offline.
            }
            return@withContext
        }
        cloneLocks.computeIfAbsent(project.id) { Mutex() }.withLock {
            if (!isCloned(project.id)) clone(project)
        }
    }

    override suspend fun fetch(projectId: String) {
        val project = find(projectId)
        if (withContext(io) { isCloned(project.id) }) fetchNow(project) else ensureCloned(projectId)
    }

    override suspend fun remove(projectId: String) {
        val project = find(projectId)
        val work = env.work
        work?.unsaved(project.id)?.let { throw ProjectException(it) }
        work?.release(project.id)
        val deleted = withContext(io) { SafeFiles.delete(dirs.bareRepo(project.id)) }
        if (!deleted) throw ProjectException("Some files of this project could not be deleted. Try again.")
        state.update { list -> list.filterNot { it.id == project.id } to Unit }
        quietly { trustState.update { it - project.id to Unit } }
    }

    override fun touched(projectId: String) = touched(projectId, clock.now())

    override fun touched(projectId: String, at: Long) {
        // A time ahead of the clock (a wrong date inside Linux) would hold the project's caches forever.
        val stamp = minOf(at, clock.now())
        scope.launch {
            quietly {
                state.update { list ->
                    list.map { p ->
                        // Minute steps are enough for day-long retention rules and spare the disk.
                        if (p.id == projectId && stamp >= p.lastActivityAt + TOUCH_STEP_MS) p.copy(lastActivityAt = stamp) else p
                    } to Unit
                }
            }
        }
    }

    override suspend fun adopt(projects: List<Project>) {
        val cloned = withContext(io) { projects.associate { it.id to isCloned(it.id) } }
        state.update { list ->
            val byId = LinkedHashMap<String, Project>()
            list.forEach { byId[it.id] = it }
            for (incoming in projects) {
                val local = byId[incoming.id]
                byId[incoming.id] = incoming.copy(
                    lastActivityAt = maxOf(incoming.lastActivityAt, local?.lastActivityAt ?: 0),
                    cloned = cloned[incoming.id] == true,
                    // An index written before the answer travelled with the project leaves it out.
                    trust = incoming.trust ?: local?.trust,
                )
            }
            byId.values.toList() to Unit
        }
        val adopted = all.value.filter { project -> projects.any { it.id == project.id } }
        trustState.update { map ->
            val unknown = adopted.filter { it.id !in map }
            (map + unknown.associate { it.id to automaticTrust(it) } + answers(adopted)) to Unit
        }
    }

    /** The owner's answer stored on the project, or null when there is none (or it is unreadable). */
    private fun answer(project: Project): ProjectTrust? =
        project.trust?.let { name -> ProjectTrust.entries.firstOrNull { it.name == name } }

    private fun answers(projects: List<Project>): Map<String, ProjectTrust> =
        projects.mapNotNull { project -> answer(project)?.let { project.id to it } }.toMap()

    /**
     * Without the owner's own answer: a repository under the signed-in account is theirs. A fork
     * is marked someone else's when it is added, as that answer.
     */
    private fun automaticTrust(project: Project): ProjectTrust {
        val login = env.gitHubAuth.account.value?.login
        return if (login != null && project.owner.equals(login, ignoreCase = true)) ProjectTrust.YOURS else ProjectTrust.SOMEONE_ELSES
    }

    private suspend fun add(info: RepoInfo): Project {
        val id = "${info.owner}/${info.name}".lowercase(Locale.ROOT)
        val now = clock.now()
        val cloned = withContext(io) { isCloned(id) }
        return state.update { list ->
            val existing = list.find { it.id == id }
            val project = existing?.copy(
                owner = info.owner,
                repo = info.name,
                defaultBranch = info.defaultBranch,
                isPrivate = info.isPrivate,
            ) ?: Project(
                id = id,
                owner = info.owner,
                repo = info.name,
                defaultBranch = info.defaultBranch,
                isPrivate = info.isPrivate,
                addedAt = now,
                lastActivityAt = now,
                cloned = cloned,
            )
            (listOf(project) + list.filterNot { it.id == id }) to project
        }
    }

    private suspend fun clone(project: Project) {
        val info = network { env.gitHub.repo(project.owner, project.repo) }
            ?: throw RepoNotReachableException(env.gitHubAuth.installUrl(), RepoAddress(project.owner, project.repo))
        val bytes = info.sizeKb * 1024
        val decision = env.dataBudget.allow(bytes, "clone", big = info.sizeKb > BIG_CLONE_KB)
        if (!decision.allowed) {
            throw ProjectException(decision.reason ?: "This project is big, so it downloads on Wi-Fi.")
        }
        val token = network { env.gitHubAuth.token() }
        val bare = dirs.bareRepo(project.id)
        // The gate builds a clone out of Linux's sight and moves it into this exact place only
        // when it is complete (it refuses any other place), so what is here now is no clone.
        withContext(io) {
            dropUnfinished(bare)
            if (!dirs.repos.isDirectory && !dirs.repos.mkdirs()) throw ProjectException("Could not create the projects folder.")
        }
        try {
            network { env.git.clone(info.cloneUrl, bare, token) }
        } catch (failure: Throwable) {
            withContext(NonCancellable + io) { dropUnfinished(bare) }
            throw failure
        }
        env.dataBudget.record(bytes, "clone")
        state.update { list ->
            list.map {
                if (it.id == project.id) {
                    it.copy(cloned = true, defaultBranch = info.defaultBranch, isPrivate = info.isPrivate)
                } else {
                    it
                }
            } to Unit
        }
    }

    private suspend fun fetchNow(project: Project) {
        val token = network { env.gitHubAuth.token() }
        network { env.git.fetch(dirs.bareRepo(project.id), token) }
    }

    private suspend fun find(projectId: String): Project =
        state.current().find { it.id == projectId } ?: throw ProjectException("This project is not on this phone.")

    private fun isCloned(projectId: String) = BareRefs(dirs.bareRepo(projectId)).isCloned()

    /** An unfinished clone (a removal cut short, a clone that failed) is not worth keeping. */
    private fun dropUnfinished(bare: File) {
        if (SafeFiles.exists(bare) && !BareRefs(bare).isCloned()) SafeFiles.delete(bare)
    }

    private fun withCloneState(projects: List<Project>) = projects.map { it.copy(cloned = isCloned(it.id)) }

    /** Runs a GitHub call, turning sign-in and connection failures into sentences the owner can act on. */
    private suspend fun <T> network(call: suspend () -> T): T = try {
        call()
    } catch (signedOut: NotConnectedException) {
        throw ProjectException("Connect GitHub first.")
    } catch (offline: IOException) {
        throw ProjectException("Could not reach GitHub. Check the connection and try again.")
    }

    /** Background bookkeeping must never crash the app; the next touch or change supersedes a failed one. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            // Nothing to report: the stored list is unchanged and still valid.
        }
    }

    private companion object {
        val REPO_NAME = Regex("[A-Za-z0-9._-]{1,100}")
        val WHITESPACE = Regex("\\s+")
        const val MAX_DESCRIPTION = 350
        const val BIG_CLONE_KB = 50L * 1024
        const val TOUCH_STEP_MS = 60_000L
    }
}
