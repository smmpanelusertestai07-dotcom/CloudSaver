package com.pocketide.cloud

import com.pocketide.core.NewComputerChoices
import com.pocketide.github.RepoInfo
import kotlinx.coroutines.flow.StateFlow

/** Where a cloud computer is in its life, as GitHub reports it (its many states, grouped). */
enum class ComputerState {
    AVAILABLE,
    CREATING,
    STARTING,
    STOPPING,
    STOPPED,

    /** Rebuilding, changing machine or exporting: start and stop wait until it is done. */
    UPDATING,
    FAILED,

    /** Deleted, archived or moved: no longer usable. */
    GONE,
    UNKNOWN,
    ;

    /** Uses the free hours right now. */
    val running: Boolean get() = this == AVAILABLE || this == STARTING || this == CREATING || this == UPDATING
}

/** A machine type GitHub offers for a repository, such as 2 cores, 8 GB RAM, 32 GB disk. */
data class Machine(
    val name: String,
    val displayName: String,
    val cpus: Int,
    val memoryBytes: Long,
    val storageBytes: Long,
)

/** The code in the computer that is not on GitHub yet: what deleting the computer would lose. */
data class GitState(val ahead: Int, val uncommitted: Boolean, val unpushed: Boolean, val branch: String?) {
    val safeToDelete: Boolean get() = !uncommitted && !unpushed && ahead == 0
}

data class RepoRef(val id: Long, val owner: String, val name: String, val isPrivate: Boolean) {
    val fullName: String get() = "$owner/$name"
}

/** One of the owner's GitHub Codespaces: a cloud computer. Times are UTC epoch ms. */
data class Computer(
    val name: String,
    val displayName: String,
    val repo: RepoRef,
    val state: ComputerState,
    /** VS Code for the web on this computer, on github.dev. */
    val webUrl: String,
    val machine: Machine?,
    val region: String?,
    val idleMinutes: Int?,
    /** How long GitHub keeps it once stopped; null when it keeps it until deleted. */
    val keepMinutes: Int?,
    /** When GitHub will delete it if it stays unused; null while it runs, or when it never will. */
    val deletesAtMs: Long?,
    val createdAtMs: Long?,
    val lastUsedAtMs: Long?,
    val git: GitState?,
    /** The dev container configuration it was made from; ours is [ComputerConfig.DEVCONTAINER]. */
    val configPath: String?,
    /** Why GitHub will not start or stop it right now, when it says. */
    val busyReason: String?,
) {
    /** Made from PocketIDE's set-up, so its agents, settings and guards are in place. */
    val setUpByPocketIde: Boolean get() = configPath == ComputerConfig.DEVCONTAINER
}

/** What the computers list shows: the owner's computers as GitHub last reported them. */
sealed interface ComputersView {
    data object Loading : ComputersView
    data class Ready(val computers: List<Computer>, val readAtMs: Long) : ComputersView
    data class Failed(val message: String, val last: List<Computer>) : ComputersView
}

/** The steps of opening a project's computer, for the progress the owner sees. */
enum class OpenStep { CHECKING, ADDING_SET_UP, CREATING, STARTING, READY }

/** Whether a repository carries PocketIDE's set-up files, and if they are current. */
enum class SetUpFiles {
    MISSING,

    /** An older PocketIDE wrote them; this version rewrites them. */
    OUTDATED,
    CURRENT,
}

/** The owner's cloud computers: GitHub Codespaces made, found, started, stopped and deleted. */
interface Computers {
    val view: StateFlow<ComputersView>

    suspend fun refresh(): List<Computer>

    suspend fun get(name: String): Computer?

    /** The machine types GitHub offers for [repo], smallest first. */
    suspend fun machines(repo: RepoInfo): List<Machine>

    suspend fun setUpFiles(repo: RepoInfo): SetUpFiles

    /**
     * The computer for [repo], started and ready: the one PocketIDE made for it, else any computer
     * of the owner's for it, else a new one. Adds or updates PocketIDE's set-up files first when
     * [addSetUp] allows it.
     */
    suspend fun openFor(repo: RepoInfo, choices: NewComputerChoices, addSetUp: Boolean, onStep: (OpenStep) -> Unit): Computer

    /** A new private repository with PocketIDE's set-up, and its computer, ready. */
    suspend fun newProject(name: String, description: String, choices: NewComputerChoices, onStep: (OpenStep) -> Unit): Computer

    /** Starts it if needed and waits until it is ready. */
    suspend fun startAndWait(name: String, onStep: (OpenStep) -> Unit): Computer

    suspend fun stop(name: String): Computer

    /** Deletes the computer and everything only it holds: chats, agent sign-ins, unpushed code. */
    suspend fun delete(name: String)
}
