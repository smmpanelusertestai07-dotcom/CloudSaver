package com.pocketide.ui.screens.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketide.builds.BuildTemplate
import com.pocketide.core.Ist
import com.pocketide.github.WorkflowRun
import com.pocketide.model.SessionRecord
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.nav.PocketNav
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Builds tab: ready-made GitHub Actions templates, run on the chosen session's branch, the
 * recent runs with their status and runner, and "Bring results to Media".
 */
@Composable
fun BuildsPanel(
    projectId: String,
    sessions: List<SessionRecord>,
    session: SessionRecord?,
    nav: PocketNav,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val templates = remember { runCatching { graph.builds.templates() }.getOrDefault(emptyList()) }
    var runs by remember(projectId) { mutableStateOf<Result<List<WorkflowRun>>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    // The run this phone started: followed by its id, never "the latest run" (which may be another one).
    var followed by rememberSaveable(projectId) { mutableStateOf<Long?>(null) }

    suspend fun loadRuns() {
        val before = runs?.getOrNull()
        val loaded = attempt { graph.builds.recentRuns(projectId) }
        runs = loaded
        // Shown from the screen's scope: the effect that called this restarts as soon as the list changes.
        finishedRun(before, loaded.getOrNull(), followed)?.let { run ->
            scope.launch { snackbar.showSnackbar("${run.name} on GitHub: ${runStatus(run).first}.") }
        }
    }
    LaunchedEffect(projectId) { loadRuns() }
    // A run GitHub gave no id for is looked for once, after GitHub had a moment to list it.
    var lookAgain by remember { mutableIntStateOf(0) }
    LaunchedEffect(lookAgain) {
        if (lookAgain == 0) return@LaunchedEffect
        delay(RUN_SETTLE_MS)
        loadRuns()
    }
    // While a run is queued or running, the list follows it on its own.
    LaunchedEffect(projectId, runs, followed) {
        val current = runs?.getOrNull() ?: return@LaunchedEffect
        if (!needsPolling(current, followed)) return@LaunchedEffect
        // GitHub lists a dispatched run a moment after the request.
        delay(if (current.none { it.id == followed }) RUN_SETTLE_MS else RUN_POLL_MS)
        loadRuns()
    }

    fun work(label: String, failed: String, block: suspend () -> String?) {
        if (busy != null) return
        busy = label
        scope.launch {
            finish { block() }
                .onSuccess { message -> message?.let { snackbar.showSnackbar(it) } }
                .onFailure { snackbar.showSnackbar("$failed: ${plainReason(it)}") }
            busy = null
        }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard(title = "Build on GitHub") {
                Text(
                    "Heavy builds (Android release, iPhone, Mac, Windows, emulator tests) run on your own GitHub Actions, " +
                        "not on the phone. Their APKs, screenshots, videos and reports come back into the session's Media.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { nav.secrets(projectId) }, label = { Text("Variables & Secrets") }, leadingIcon = { Icon(Icons.Filled.Key, null, Modifier.size(18.dp)) })
                    AssistChip(onClick = { nav.schedules(projectId) }, label = { Text("Scheduled tasks") }, leadingIcon = { Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp)) })
                }
            }
        }
        item {
            SectionLabel("Templates")
            if (session == null) {
                Text(
                    "Start a session first: a build runs on a session's branch.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (templates.isEmpty()) {
            item { Text("No build templates are available.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(templates, key = { it.id }) { template ->
            TemplateCard(
                template = template,
                session = session,
                busy = busy != null,
                onAdd = { target ->
                    work("add", "Could not add the template") {
                        graph.builds.addTemplate(projectId, target.id, template.id)
                        "Added ${template.fileName} to \"${target.title}\"."
                    }
                },
                onRun = { target ->
                    work("run", "Could not start the build") {
                        val runId = graph.builds.run(projectId, template.id, target.branch)
                        followed = runId
                        if (runId == null) {
                            lookAgain++
                            "${template.title} was sent to GitHub for \"${target.title}\". It shows here once GitHub lists it."
                        } else {
                            "${template.title} started on GitHub for \"${target.title}\"."
                        }
                    }
                },
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Recent runs", Modifier.weight(1f))
                if (busy != null) CircularProgressIndicator(Modifier.size(20.dp))
                IconButton(onClick = { scope.launch { loadRuns() } }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh runs") }
            }
        }
        val result = runs
        when {
            result == null -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            result.isFailure -> item {
                Text(
                    "Could not load runs: ${plainReason(result.exceptionOrNull() ?: IllegalStateException())}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            result.getOrThrow().isEmpty() -> item {
                EmptyState(Icons.Filled.Build, "No builds yet", "Runs of this project's workflows on GitHub appear here.")
            }
            else -> items(followedFirst(result.getOrThrow(), followed), key = { it.id }) { run ->
                RunCard(
                    run = run,
                    mine = run.id == followed,
                    target = sessionForBranch(sessions, run.branch) ?: session,
                    busy = busy != null,
                    onCollect = { target ->
                        work("collect", "Could not bring the results") {
                            val count = graph.builds.collect(projectId, target.id, run.id)
                            if (count == 0) "This run has no files to bring." else "${WorkFormat.count(count, "file", "files")} added to Media of \"${target.title}\"."
                        }
                    },
                    onOpen = { nav.openExternal(run.htmlUrl) },
                )
            }
        }
    }
}

/** How often a queued or running build is checked while this tab is open. */
private const val RUN_POLL_MS = 15_000L
private const val RUN_SETTLE_MS = 3_000L

@Composable
private fun TemplateCard(
    template: BuildTemplate,
    session: SessionRecord?,
    busy: Boolean,
    onAdd: (SessionRecord) -> Unit,
    onRun: (SessionRecord) -> Unit,
) {
    SectionCard(title = template.title) {
        Text(template.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Runs on ${template.runner} · ${template.fileName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (session != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { onRun(session) }) { Text("Run") }
                OutlinedButton(enabled = !busy, onClick = { onAdd(session) }) { Text("Add to session") }
            }
            Text(
                "On branch ${session.branch}. Add the template once, then Run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RunCard(run: WorkflowRun, mine: Boolean, target: SessionRecord?, busy: Boolean, onCollect: (SessionRecord) -> Unit, onOpen: () -> Unit) {
    val (label, tone) = runStatus(run)
    SectionCard(title = null) {
        if (mine) Text("Started from this phone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(run.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(run.branch, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusChip(label, tone)
        }
        val started = isoToEpoch(run.createdAt)?.let { Ist.dateTime(it) } ?: run.createdAt
        Text(
            listOfNotNull(started, run.runnerImage?.let { "Runner: $it" }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (run.status == "completed" && target != null) {
                OutlinedButton(enabled = !busy, onClick = { onCollect(target) }) { Text("Bring results to Media") }
            }
            TextButton(onClick = onOpen) { Text("Open on GitHub") }
        }
    }
}
