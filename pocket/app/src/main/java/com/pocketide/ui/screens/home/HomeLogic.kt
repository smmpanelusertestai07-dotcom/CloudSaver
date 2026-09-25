package com.pocketide.ui.screens.home

import android.provider.Settings
import com.pocketide.github.RepoInfo
import com.pocketide.model.Thermal
import com.pocketide.projects.RepoAddress
import com.pocketide.rooms.RoomState
import com.pocketide.sync.BackupState
import com.pocketide.sync.SessionBackup
import com.pocketide.sync.SyncStatus
import com.pocketide.ui.components.Tone
import com.pocketide.ui.screens.project.WorkFormat

/** Repositories matching [query] (owner or name), those not yet added first, then by last push. */
fun filterRepos(repos: List<RepoInfo>, query: String, addedIds: Set<String>): List<RepoInfo> {
    val q = query.trim().lowercase()
    return repos
        .filter { q.isEmpty() || "${it.owner}/${it.name}".lowercase().contains(q) }
        .sortedWith(
            compareBy<RepoInfo> { repoId(it) in addedIds }
                .thenByDescending { it.pushedAt.orEmpty() }
                .thenBy { it.name.lowercase() },
        )
}

/** A project's id for a repository: "<owner>/<repo>", lower case. */
fun repoId(repo: RepoInfo): String = "${repo.owner}/${repo.name}".lowercase()

private val REPO_NAME = Regex("[A-Za-z0-9._-]{1,100}")

/** Why [name] cannot be a GitHub repository name, or null when it can. */
fun repoNameProblem(name: String): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "Give the project a name."
        trimmed == "." || trimmed == ".." -> "That name is reserved by GitHub."
        !REPO_NAME.matches(trimmed) -> "Use letters, numbers, dots, dashes and underscores only (up to 100)."
        trimmed.endsWith(".git", ignoreCase = true) -> "The name cannot end in .git."
        else -> null
    }
}

/** The phone's heat in the words the phone strip shows. */
fun heatLabel(thermal: Thermal): Pair<String, Tone> = when (thermal) {
    Thermal.NONE -> "Cool" to Tone.OK
    Thermal.LIGHT -> "Warm" to Tone.OK
    Thermal.MODERATE -> "Hot" to Tone.WARN
    Thermal.SEVERE -> "Very hot" to Tone.ERROR
    Thermal.CRITICAL, Thermal.EMERGENCY, Thermal.SHUTDOWN -> "Too hot" to Tone.ERROR
}

fun batteryLabel(percent: Int, charging: Boolean): Pair<String, Tone> {
    val tone = when {
        charging || percent > 20 -> Tone.OK
        percent > 10 -> Tone.WARN
        else -> Tone.ERROR
    }
    return (if (charging) "$percent% charging" else "$percent%") to tone
}

/** An agent's room in the words its card shows. */
fun roomLabel(state: RoomState?): Pair<String, Tone> = when (state) {
    null, RoomState.Stopped -> "Ready" to Tone.NEUTRAL
    is RoomState.Starting -> state.step to Tone.WARN
    is RoomState.Running -> "Running · ${WorkFormat.bytes(state.memoryBytes)}" to Tone.OK
    is RoomState.Failed -> "Stopped: ${state.why}" to Tone.ERROR
}

/**
 * An agent's sign-in in the words its card shows; null when PocketIDE cannot see it. The agent
 * signs in inside its own screen, so "Not signed in" also covers one never signed in yet.
 */
fun signInLabel(signedIn: Boolean?): Pair<String, Tone>? = when (signedIn) {
    true -> "Signed in" to Tone.OK
    false -> "Not signed in" to Tone.WARN
    null -> null
}

/** Settings pages that need "package:<app>" to open on this app's own entry. */
private val PACKAGE_SCOPED_ACTIONS = setOf(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
    Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
)

fun needsPackageUri(action: String): Boolean = action in PACKAGE_SCOPED_ACTIONS

/**
 * An address pasted into the Import search (owner/repo, or any GitHub link form) that the list
 * does not show, so it can be imported directly. Null for plain search words and other hosts.
 */
fun pastedRepo(query: String, listed: List<RepoInfo>): RepoAddress? {
    if ('/' !in query && ':' !in query) return null
    val address = RepoAddress.parse(query) ?: return null
    val id = "${address.owner}/${address.repo}".lowercase()
    return address.takeIf { listed.none { repoId(it) == id } }
}

/** Where an agent's limits come from, and the vendor's own usage page when there is one. */
data class AgentLimits(val text: String, val usageUrl: String?)

fun agentLimits(agentId: String): AgentLimits = when (agentId) {
    "claude" -> AgentLimits("Limits come from your Claude plan.", "https://claude.ai/settings/usage")
    "codex" -> AgentLimits("Limits come from your ChatGPT plan.", "https://chatgpt.com/codex/settings/usage")
    "antigravity" -> AgentLimits("Limits come from your Google plan.", null)
    else -> AgentLimits("Limits come from the service this agent uses.", null)
}

/**
 * The small backup dot on Home: its colour, and what it means for the screen reader. Between
 * syncs, each session's own backup state ([backups]) says whether something still waits.
 */
fun syncDot(status: SyncStatus, backups: Collection<SessionBackup> = emptyList()): Pair<Tone, String> = when (status) {
    is SyncStatus.Error -> Tone.ERROR to "Backup is not working"
    is SyncStatus.Waiting -> Tone.WARN to "Chats are waiting to back up"
    is SyncStatus.Running -> Tone.NEUTRAL to "Backing up now"
    is SyncStatus.UpToDate, SyncStatus.Idle -> when {
        backups.any { it.state == BackupState.WAITING } -> Tone.WARN to "Chats are waiting to back up"
        backups.any { it.state == BackupState.WAITING_FOR_WIFI } -> Tone.NEUTRAL to "Videos are waiting for Wi-Fi"
        else -> Tone.OK to "Backed up"
    }
}
