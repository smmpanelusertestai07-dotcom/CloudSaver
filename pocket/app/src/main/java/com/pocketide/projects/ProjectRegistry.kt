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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The projects on this phone, kept in `vault/projects.json`. GitHub holds the code: the phone
 * keeps a bare clone per project, made the first time the project is opened.
 */
internal class ProjectRegistry(
    private val env: ProjectEnv,
    private val dirs: AppDirs,
    file: JsonFile<List<Project>>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : Projects {

    private val state = JsonState(file, emptyList(), io, normalize = ::withCloneState)
    private val cloneLocks = ConcurrentHashMap<String, Mutex>()

    override val all: StateFlow<List<Project>> = state.flow

    init {
        scope.launch { quietly { state.current() } }
    }

    override suspend fun create(name: String, description: String): Project {
        val repoName = name.trim()
        if (!REPO_NAME.matches(repoName) || repoName == "." || repoName == "..") {
            throw ProjectException("Use letters, numbers, dots, hyphens or underscores for the name, up to 100 characters.")
        }
        val about = description.trim().replace(Regex("\\s+"), " ").take(MAX_DESCRIPTION)
        return add(network { env.gitHub.createPrivateRepo(repoName, about) })
    }

    override suspend fun import(owner: String, repo: String): Project {
        val address = RepoAddress.parse(if (repo.isBlank()) owner else "${owner.trim()}/${repo.trim()}")
            ?: throw ProjectException("Check the repository: use owner/name, or paste its GitHub address.")
        val info = network { env.gitHub.repo(address.owner, address.repo) }
            ?: throw RepoNotReachableException(env.gitHubAuth.installUrl())
        return add(info)
    }

    override suspend fun ensureCloned(projectId: String) {
        val project = find(projectId)
        if (isCloned(project.id)) {
            try {
                fetchNow(project)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (offline: Exception) {
                // The clone on the phone is used as it is; agents keep working offline.
            }
            return
        }
        cloneLocks.computeIfAbsent(project.id) { Mutex() }.withLock {
            if (!isCloned(project.id)) clone(project)
        }
    }

    override suspend fun fetch(projectId: String) {
        val project = find(projectId)
        if (isCloned(project.id)) fetchNow(project) else ensureCloned(projectId)
    }

    override suspend fun remove(projectId: String) {
        val project = find(projectId)
        val work = env.work
        work?.unsaved(project.id)?.let { throw ProjectException(it) }
        work?.release(project.id)
        val deleted = withContext(io) { SafeFiles.delete(dirs.bareRepo(project.id)) }
        if (!deleted) throw ProjectException("Some files of this project could not be deleted. Try again.")
        state.update { list -> list.filterNot { it.id == project.id } to Unit }
    }

    override fun touched(projectId: String) = touched(projectId, clock.now())

    override fun touched(projectId: String, at: Long) {
        scope.launch {
            quietly {
                state.update { list ->
                    list.map { p ->
                        // Minute steps are enough for day-long retention rules and spare the disk.
                        if (p.id == projectId && at >= p.lastActivityAt + TOUCH_STEP_MS) p.copy(lastActivityAt = at) else p
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
                )
            }
            byId.values.toList() to Unit
        }
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
            ?: throw RepoNotReachableException(env.gitHubAuth.installUrl())
        val bytes = info.sizeKb * 1024
        val decision = env.dataBudget.allow(bytes, "clone", big = info.sizeKb > BIG_CLONE_KB)
        if (!decision.allowed) {
            throw ProjectException(decision.reason ?: "This project is big, so it downloads on Wi-Fi.")
        }
        val token = network { env.gitHubAuth.token() }
        val bare = dirs.bareRepo(project.id)
        // The clone is made under another name and renamed at the end, so an interrupted clone
        // never looks like a finished one.
        val partial = File(dirs.repos, ".${bare.name}.partial")
        withContext(io) {
            SafeFiles.delete(partial)
            if (!dirs.repos.isDirectory && !dirs.repos.mkdirs()) throw ProjectException("Could not create the projects folder.")
        }
        try {
            network { env.git.clone(info.cloneUrl, partial, token) }
            withContext(io) {
                if (SafeFiles.exists(bare)) SafeFiles.delete(bare)
                Files.move(partial.toPath(), bare.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + io) { SafeFiles.delete(partial) }
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
        const val MAX_DESCRIPTION = 350
        const val BIG_CLONE_KB = 50L * 1024
        const val TOUCH_STEP_MS = 60_000L
    }
}
