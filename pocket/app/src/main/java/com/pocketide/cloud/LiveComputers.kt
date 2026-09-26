package com.pocketide.cloud

import com.pocketide.core.Clock
import com.pocketide.core.NewComputerChoices
import com.pocketide.github.GitHubApi
import com.pocketide.github.GitHubException
import com.pocketide.github.RepoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Thrown by [Computers.openFor] when the repository needs PocketIDE's set-up files and the owner has not agreed yet. */
class SetUpNeededException(val files: SetUpFiles) : Exception("PocketIDE's set-up files need adding to this repository first.")

/**
 * [Computers] over GitHub's Codespaces API. GitHub is the only record: the list is read from it
 * each time, and nothing about a computer is kept on the phone.
 */
internal class LiveComputers(
    private val api: CodespacesRest,
    private val gitHub: GitHubApi,
    private val clock: Clock,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : Computers {
    private val state = MutableStateFlow<ComputersView>(ComputersView.Loading)
    override val view: StateFlow<ComputersView> = state.asStateFlow()

    override suspend fun refresh(): List<Computer> = try {
        val computers = mapped { api.list() }
            .filter { it.state != ComputerState.GONE }
            .sortedByDescending { it.lastUsedAtMs ?: it.createdAtMs ?: 0L }
        state.value = ComputersView.Ready(computers, clock.now())
        computers
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        state.value = ComputersView.Failed(e.message ?: CloudText.NOT_FOUND, known())
        throw e
    }

    override suspend fun get(name: String): Computer? = mapped { api.get(name) }?.also(::remember)

    override suspend fun machines(repo: RepoInfo): List<Machine> =
        mapped { api.machines(repo.owner, repo.name) }.sortedWith(compareBy({ it.cpus }, { it.memoryBytes }))

    override suspend fun setUpFiles(repo: RepoInfo): SetUpFiles {
        val file = gitHub.readFile(repo.owner, repo.name, ComputerConfig.DEVCONTAINER, repo.defaultBranch)
        return ComputerConfig.status(file?.bytes?.decodeToString())
    }

    override suspend fun openFor(repo: RepoInfo, choices: NewComputerChoices, addSetUp: Boolean, onStep: (OpenStep) -> Unit): Computer {
        onStep(OpenStep.CHECKING)
        val theirs = refresh().filter { it.repo.id == repo.id }
        val existing = theirs.firstOrNull { it.setUpByPocketIde } ?: theirs.firstOrNull()
        if (existing != null) return startAndWait(existing.name, onStep)

        val files = setUpFiles(repo)
        if (files != SetUpFiles.CURRENT) {
            if (!addSetUp) throw SetUpNeededException(files)
            onStep(OpenStep.ADDING_SET_UP)
            gitHub.commitFiles(repo.owner, repo.name, repo.defaultBranch, ComputerConfig.files(), COMMIT_MESSAGE)
        }
        return create(repo, choices, onStep)
    }

    override suspend fun newProject(name: String, description: String, choices: NewComputerChoices, onStep: (OpenStep) -> Unit): Computer {
        onStep(OpenStep.ADDING_SET_UP)
        val repo = gitHub.createPrivateRepo(name, description)
        commitWhenReady(repo)
        return create(repo, choices, onStep)
    }

    override suspend fun startAndWait(name: String, onStep: (OpenStep) -> Unit): Computer {
        var asked = false
        val deadline = clock.now() + READY_WITHIN_MS
        while (true) {
            val computer = get(name) ?: throw GitHubException(CloudText.NOT_FOUND, HttpStatus.NOT_FOUND)
            when (computer.state) {
                ComputerState.AVAILABLE -> {
                    onStep(OpenStep.READY)
                    return computer
                }
                ComputerState.STOPPED -> if (!asked) {
                    onStep(OpenStep.STARTING)
                    remember(mapped { api.start(name) })
                    asked = true
                }
                ComputerState.CREATING -> onStep(OpenStep.CREATING)
                ComputerState.FAILED -> throw GitHubException(CloudText.FAILED, HttpStatus.NONE)
                ComputerState.GONE -> throw GitHubException(CloudText.NOT_FOUND, HttpStatus.NOT_FOUND)
                // Stopping: once it has stopped, the next round starts it again.
                ComputerState.STARTING, ComputerState.STOPPING, ComputerState.UPDATING, ComputerState.UNKNOWN -> onStep(OpenStep.STARTING)
            }
            if (clock.now() > deadline) throw GitHubException(CloudText.TOO_SLOW, HttpStatus.NONE)
            pause(POLL_MS)
        }
    }

    override suspend fun stop(name: String): Computer = mapped { api.stop(name) }.also(::remember)

    override suspend fun delete(name: String) {
        mapped { api.delete(name) }
        state.update { view ->
            when (view) {
                is ComputersView.Ready -> view.copy(computers = view.computers.filterNot { it.name == name })
                is ComputersView.Failed -> view.copy(last = view.last.filterNot { it.name == name })
                ComputersView.Loading -> view
            }
        }
    }

    private suspend fun create(repo: RepoInfo, choices: NewComputerChoices, onStep: (OpenStep) -> Unit): Computer {
        onStep(OpenStep.CREATING)
        val request = NewCodespace(
            repositoryId = repo.id,
            branch = repo.defaultBranch,
            displayName = displayName(repo.name),
            devcontainerPath = ComputerConfig.DEVCONTAINER,
            machine = choices.machine.ifBlank { null },
            idleMinutes = choices.idleMinutes.coerceIn(MIN_IDLE_MINUTES, MAX_IDLE_MINUTES),
            keepMinutes = choices.keepDays.coerceIn(0, MAX_KEEP_DAYS) * MINUTES_PER_DAY,
        )
        val made = mapped { api.create(request) }
        remember(made)
        return startAndWait(made.name, onStep)
    }

    /** A brand-new repository's first commit can take GitHub a moment; the set-up waits for it. */
    private suspend fun commitWhenReady(repo: RepoInfo) {
        repeat(COMMIT_ATTEMPTS) { attempt ->
            try {
                gitHub.commitFiles(repo.owner, repo.name, repo.defaultBranch, ComputerConfig.files(), COMMIT_MESSAGE)
                return
            } catch (e: GitHubException) {
                if (e.status != HttpStatus.NOT_FOUND && e.status != HttpStatus.CONFLICT) throw e
                if (attempt == COMMIT_ATTEMPTS - 1) throw GitHubException(CloudText.REPO_NOT_READY, e.status)
                pause(POLL_MS)
            }
        }
    }

    /** Puts GitHub's latest word about one computer into the list the screens show. */
    private fun remember(computer: Computer) {
        state.update { view ->
            when (view) {
                is ComputersView.Ready -> view.copy(computers = view.computers.replacing(computer))
                is ComputersView.Failed -> view.copy(last = view.last.replacing(computer))
                ComputersView.Loading -> view
            }
        }
    }

    private fun known(): List<Computer> = when (val view = state.value) {
        is ComputersView.Ready -> view.computers
        is ComputersView.Failed -> view.last
        ComputersView.Loading -> emptyList()
    }

    private suspend fun <T> mapped(call: suspend () -> T): T = try {
        call()
    } catch (e: GitHubException) {
        throw CloudErrors.of(e)
    }

    companion object {
        const val COMMIT_MESSAGE = "Set up PocketIDE's cloud computer"
        const val POLL_MS = 3_000L

        /** GitHub usually has a computer ready in one to three minutes; a first start with a new image can take longer. */
        const val READY_WITHIN_MS = 15 * 60_000L
        const val MIN_IDLE_MINUTES = 5
        const val MAX_IDLE_MINUTES = 240
        const val MAX_KEEP_DAYS = 30
        const val MINUTES_PER_DAY = 24 * 60

        /** GitHub's limit for a codespace's display name. */
        const val DISPLAY_NAME_MAX = 48
        private const val COMMIT_ATTEMPTS = 5

        fun displayName(repo: String): String = "PocketIDE · $repo".take(DISPLAY_NAME_MAX)

        private fun List<Computer>.replacing(computer: Computer): List<Computer> =
            if (any { it.name == computer.name }) map { if (it.name == computer.name) computer else it } else listOf(computer) + this
    }
}
