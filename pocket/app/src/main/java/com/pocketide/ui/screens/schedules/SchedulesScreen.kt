package com.pocketide.ui.screens.schedules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.core.Ist
import com.pocketide.model.AgentInfo
import com.pocketide.model.PhoneSnapshot
import com.pocketide.model.Project
import com.pocketide.schedule.ScheduledTask
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.manage.ActionRunner
import com.pocketide.ui.manage.ConfirmDialog
import com.pocketide.ui.manage.EmptyNote
import com.pocketide.ui.manage.Hint
import com.pocketide.ui.manage.ManageFormat
import com.pocketide.ui.manage.ManagePage
import com.pocketide.ui.manage.ScheduleForm
import com.pocketide.ui.manage.rememberActionRunner
import com.pocketide.ui.manage.rememberGraph
import com.pocketide.ui.nav.PocketNav
import java.util.UUID

private const val RULE = "Scheduled tasks run only while the phone is charging and on Wi-Fi, so they never drain the battery or use mobile data."
private val EVERY_CHOICES = listOf(6, 12, 24, 168)

/**
 * Saved prompts that an agent runs through its own command-line mode while the phone charges on
 * Wi-Fi. Each run becomes a session to review. Shows one project's tasks, or all of them.
 */
@Composable
fun SchedulesScreen(projectId: String?, nav: PocketNav) {
    val graph = rememberGraph()
    val runner = rememberActionRunner()
    val all by graph.schedules.tasks.collectAsStateWithLifecycle()
    val projects by graph.projects.all.collectAsStateWithLifecycle()
    val agents by graph.agents.installed.collectAsStateWithLifecycle()
    val snapshot by graph.phone.snapshot.collectAsStateWithLifecycle()
    val tasks = remember(all, projectId) { all.filter { projectId == null || it.projectId == projectId }.sortedBy { it.title.lowercase() } }
    var editing by remember { mutableStateOf<ScheduledTask?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ScheduledTask?>(null) }
    var refused by remember { mutableStateOf<String?>(null) }

    fun runNow(task: ScheduledTask) {
        val reason = whyNotNow(snapshot)
        if (reason != null) {
            refused = reason
            return
        }
        runner.run(
            key = "run:${task.id}",
            onSuccess = { sessionId: String? ->
                if (sessionId == null) refused = "It could not start now." else nav.transcript(sessionId)
            },
        ) { graph.schedules.runNow(task.id) }
    }

    ManagePage("Scheduled tasks", nav, runner) {
        item { SectionCard(null) { Hint(RULE) } }
        item {
            Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth(), enabled = projects.isNotEmpty() && agents.isNotEmpty()) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New scheduled task")
            }
        }
        if (projects.isEmpty()) item { Hint("Add a project first: every task works on one project.") }
        if (tasks.isEmpty()) {
            item { EmptyNote(Icons.Outlined.Schedule, "No scheduled tasks", "For example: every day, update dependencies and run the tests.") }
        }
        items(tasks, key = { it.id }) { task ->
            TaskCard(
                task = task,
                project = projects.firstOrNull { it.id == task.projectId },
                agent = agents.firstOrNull { it.id == task.agentId },
                now = graph.clock.now(),
                runner = runner,
                onToggle = { on -> runner.run("save:${task.id}") { graph.schedules.save(task.copy(enabled = on)) } },
                onRun = { runNow(task) },
                onEdit = { editing = task },
                onDelete = { deleting = task },
                onOpenSession = nav::transcript,
            )
        }
    }

    if (adding || editing != null) {
        TaskEditor(
            existing = editing,
            fixedProjectId = projectId,
            projects = projects,
            agents = agents,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { task ->
                adding = false
                editing = null
                runner.run("save:${task.id}", done = "\"${task.title}\" saved.") { graph.schedules.save(task) }
            },
        )
    }
    deleting?.let { task ->
        ConfirmDialog(
            title = "Delete \"${task.title}\"?",
            text = "The task stops running. Sessions it already made stay in Chats.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { runner.run("delete:${task.id}", done = "Task deleted.") { graph.schedules.remove(task.id) } },
            onDismiss = { deleting = null },
        )
    }
    refused?.let { why ->
        AlertDialog(
            onDismissRequest = { refused = null },
            title = { Text("Not now") },
            text = { Text("$why $RULE It runs by itself at the next chance.") },
            confirmButton = { TextButton(onClick = { refused = null }) { Text("OK") } },
        )
    }
}

/** The charging and Wi-Fi rule, checked before asking the module, so the owner hears why at once. */
private fun whyNotNow(s: PhoneSnapshot): String? = when {
    s.at == 0L -> null
    !s.charging && (!s.online || s.metered) -> "The phone is not charging and not on Wi-Fi."
    !s.charging -> "The phone is not charging."
    !s.online || s.metered -> "The phone is not on Wi-Fi."
    else -> null
}

@Composable
private fun TaskCard(
    task: ScheduledTask,
    project: Project?,
    agent: AgentInfo?,
    now: Long,
    runner: ActionRunner,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Hint("${agent?.displayName ?: task.agentId} · ${project?.repo ?: task.projectId} · ${ManageFormat.every(task.everyHours).lowercase()}")
            }
            Switch(checked = task.enabled, onCheckedChange = onToggle, enabled = !runner.isBusy("save:${task.id}"))
        }
        Text(task.prompt, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val lastRun = task.lastRunAt
        Hint(if (lastRun == null) "Not run yet." else "Last run ${Ist.dateTime(lastRun)}.")
        val due = ScheduleForm.nextDueAt(task, now)
        if (due != null) Hint("Next ${ManageFormat.inFuture(due - now)}, when charging on Wi-Fi.")
        task.lastSessionId?.let { session ->
            TextButton(onClick = { onOpenSession(session) }) { Text("Open the last run's session") }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRun, enabled = !runner.isBusy("run:${task.id}")) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (runner.isBusy("run:${task.id}")) "Starting…" else "Run now")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Change ${task.title}") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${task.title}") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskEditor(
    existing: ScheduledTask?,
    fixedProjectId: String?,
    projects: List<Project>,
    agents: List<AgentInfo>,
    onDismiss: () -> Unit,
    onSave: (ScheduledTask) -> Unit,
) {
    var projectId by remember { mutableStateOf(existing?.projectId ?: fixedProjectId ?: projects.singleOrNull()?.id) }
    var agentId by remember { mutableStateOf(existing?.agentId ?: agents.firstOrNull { it.official }?.id) }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var prompt by remember { mutableStateOf(existing?.prompt.orEmpty()) }
    var hours by remember { mutableStateOf((existing?.everyHours ?: 24).toString()) }
    var tried by remember { mutableStateOf(false) }
    val problem = ScheduleForm.problem(title, prompt, hours.toIntOrNull(), projectId, agentId)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New scheduled task" else "Change task") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (fixedProjectId == null || existing != null) {
                    Text("Project", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        projects.forEach { p ->
                            FilterChip(selected = p.id == projectId, onClick = { projectId = p.id }, label = { Text(p.repo) })
                        }
                    }
                }
                Text("Agent", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    agents.forEach { a ->
                        FilterChip(selected = a.id == agentId, onClick = { agentId = a.id }, label = { Text(a.displayName) })
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(ScheduleForm.MAX_TITLE) },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("What the agent should do") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                )
                Text("How often", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EVERY_CHOICES.forEach { h ->
                        FilterChip(selected = hours == h.toString(), onClick = { hours = h.toString() }, label = { Text(ManageFormat.every(h)) })
                    }
                }
                OutlinedTextField(
                    value = hours,
                    onValueChange = { hours = it.filter(Char::isDigit).take(3) },
                    label = { Text("Every how many hours") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (tried && problem != null) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tried = true
                val project = projectId
                val agent = agentId
                val every = hours.toIntOrNull()
                if (problem == null && project != null && agent != null && every != null) {
                    val base = existing ?: ScheduledTask(UUID.randomUUID().toString(), project, agent, "", "", every)
                    onSave(base.copy(projectId = project, agentId = agent, title = title.trim(), prompt = prompt.trim(), everyHours = every))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
