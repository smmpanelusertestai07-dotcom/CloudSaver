package com.pocketide.ui.screens.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketide.github.PullRequest
import com.pocketide.github.WorkflowRun
import com.pocketide.model.SessionRecord
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone

/** A session's pull request (found by its branch) and the latest GitHub Actions run on that branch. */
data class SessionPull(val pull: PullRequest?, val run: WorkflowRun?)

/** A pull request's state in plain words: open (and whether it can merge), merged, or closed. */
fun pullStatus(pull: PullRequest): Pair<String, Tone> = when {
    pull.merged -> "Merged" to Tone.OK
    pull.state == "open" && pull.mergeable == false -> "Open · has conflicts" to Tone.WARN
    pull.state == "open" -> "Open" to Tone.OK
    else -> "Closed without merging" to Tone.NEUTRAL
}

/** The newest run on [branch], from GitHub's list (newest first). */
fun latestRunOn(runs: List<WorkflowRun>, branch: String): WorkflowRun? = runs.firstOrNull { it.branch == branch }

/**
 * The pull request and checks of [session], read from GitHub when shown: the pull request is
 * found by the session's branch, then read in full for its merge state. Each row opens GitHub.
 */
@Composable
fun SessionPullRows(session: SessionRecord, onOpen: (String) -> Unit) {
    val graph = rememberGraph()
    val loaded by produceState<Result<SessionPull>?>(null, session.id, session.branch) {
        value = attempt {
            val project = graph.projects.all.value.firstOrNull { it.id == session.projectId }
                ?: throw IllegalStateException("This session's project is not on this phone.")
            val found = graph.gitHub.pullRequestFor(project.owner, project.repo, session.branch)
            val pull = found?.let { graph.gitHub.pullRequest(project.owner, project.repo, it.number) }
            SessionPull(pull, latestRunOn(graph.builds.recentRuns(project.id), session.branch))
        }
    }
    val result = loaded
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            result == null -> Hint("Checking GitHub…")
            result.isFailure -> Hint("GitHub could not be checked: ${plainReason(result.exceptionOrNull() ?: IllegalStateException())}")
            else -> {
                val (pull, run) = result.getOrThrow()
                if (pull == null) {
                    Hint("Pull request: none yet. The agent can open one with open_pr.")
                } else {
                    val (text, tone) = pullStatus(pull)
                    LinkRow("Pull request #${pull.number}", text, tone) { onOpen(pull.url) }
                }
                if (run == null) {
                    Hint("Checks: no GitHub Actions run on this branch yet.")
                } else {
                    val (text, tone) = runStatus(run)
                    LinkRow("Checks · ${run.name}", text, tone) { onOpen(run.htmlUrl) }
                }
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, status: String, tone: Tone, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(12.dp))
        StatusChip(status, tone)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
