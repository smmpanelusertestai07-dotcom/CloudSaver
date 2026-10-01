package com.pocketide.ui.screens.usage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.agents.Agent
import com.pocketide.cloudshell.AgentStatus
import com.pocketide.cloudshell.AgentUsage
import com.pocketide.cloudshell.Answer
import com.pocketide.cloudshell.CloudShellInfo
import com.pocketide.cloudshell.MachineStatus
import com.pocketide.cloudshell.UsageReport
import com.pocketide.core.connectedFor
import com.pocketide.docs.DocLinks
import com.pocketide.graph
import com.pocketide.link.LinkState
import com.pocketide.ui.components.AgentLogo
import com.pocketide.ui.components.Formats
import com.pocketide.ui.components.InfoRow
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.Tone
import com.pocketide.ui.screens.live.Asking
import com.pocketide.ui.screens.live.ConnectFirst
import com.pocketide.ui.screens.live.KeepConnectionWhileShown
import com.pocketide.ui.shell.FinePrint
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.ShellPage
import com.pocketide.ui.web.Browser
import java.util.Locale

/**
 * Usage, live: Cloud Shell's machine now, the week's hours against Google's 50, and what each agent
 * used, from its own files in Cloud Shell, with each company's own page for the plan's limits.
 */
@Composable
fun UsageScreen() {
    val context = LocalContext.current
    val graph = context.graph
    val link by graph.link.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    var machine by remember { mutableStateOf<Answer<MachineStatus>?>(null) }
    var usage by remember { mutableStateOf<Answer<UsageReport>?>(null) }
    var asking by remember { mutableStateOf(false) }
    var again by remember { mutableIntStateOf(0) }
    KeepConnectionWhileShown()
    LifecycleStartEffect(Unit) {
        again++
        onStopOrDispose { }
    }
    LaunchedEffect(link == LinkState.On, again) {
        if (link == LinkState.On) {
            asking = true
            machine = graph.cloudInfo.status()
            usage = graph.cloudInfo.usage()
            asking = false
        }
    }
    val now = graph.clock.now()
    ShellPage {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.DataUsage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                "Usage",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = { again++ }, enabled = link == LinkState.On && !asking) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Read again")
            }
        }
        Text(
            "Cloud Shell and the agents, as they are now.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Gap(16.dp)
        Week(settings.connectedFor(now, WEEK_MS, graph.link.connectedSince))
        Gap(12.dp)
        when {
            link != LinkState.On -> ConnectFirst(link, "Cloud Shell's numbers and the agents' usage are")
            asking && machine == null -> Asking("Reading Cloud Shell's numbers…")
            else -> {
                when (val shown = machine) {
                    is Answer.Got -> Machine(shown.value)
                    is Answer.Failed -> NoticeCard(shown.why, tone = Tone.WARN)
                    else -> Unit
                }
                Gap(12.dp)
                when (val shown = usage) {
                    is Answer.Got -> Agents(shown.value, (machine as? Answer.Got)?.value)
                    is Answer.Failed -> NoticeCard(shown.why, tone = Tone.WARN)
                    else -> Unit
                }
            }
        }
        FinePrint(
            "Read from Cloud Shell over PocketIDE's private connection when you open this page; tokens are what each " +
                "agent wrote in its own files there. The plans' limits are the AI companies' own: their pages show them. " +
                "PocketIDE never reads a sign-in.",
        )
    }
}

/** The week's hours: PocketIDE's own count, against Google's 50. */
@Composable
private fun Week(connected: Long) {
    val context = LocalContext.current
    val hours = connected / HOUR_MS.toDouble()
    SectionCard("This week") {
        Text("Connected through PocketIDE: ${duration(connected)} of the last 7 days", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { (hours / WEEKLY_HOURS).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text(
            "Google allows Cloud Shell $WEEKLY_HOURS hours a week, and counts a little more than this: until Cloud Shell stops " +
                "by itself after you leave. Google's own count, with the hours left and when they reset: Cloud Shell's " +
                "page, Session information, Usage quota.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { Browser.open(context, DocLinks.CLOUD_SHELL_QUOTA) }) { Text("Google's limits") }
    }
}

/** Cloud Shell's machine now: how long this session has run, its memory, its home folder. */
@Composable
private fun Machine(status: MachineStatus) {
    SectionCard("Cloud Shell now") {
        val ran = status.uptimeSeconds * SECOND_MS
        Meter("This session: ${duration(ran)} of Google's 12 hours", ran / SESSION_MS.toDouble())
        val used = status.memoryTotal - status.memoryAvailable
        Meter("Memory: ${Formats.size(used)} used of ${Formats.size(status.memoryTotal)}", share(used, status.memoryTotal))
        Meter("Home folder: ${Formats.size(status.homeUsed)} used of ${Formats.size(status.homeTotal)}", share(status.homeUsed, status.homeTotal))
        InfoRow("Processors", "${status.processors}, busy ${status.load.firstOrNull()?.let { String.format(Locale.ENGLISH, "%.2f", it) } ?: "?"}")
        status.codeServer?.let { InfoRow("VS Code (code-server)", it) }
        if (status.updated > 0) InfoRow("Agents last updated", Formats.ago(status.updated, status.now))
    }
}

@Composable
private fun Meter(text: String, fraction: Double) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { fraction.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/** Each agent: its VS Code, its sign-in, what it used, and its company's page of the plan's limits. */
@Composable
private fun Agents(report: UsageReport, machine: MachineStatus?) {
    Agent.entries.forEach { agent ->
        AgentCard(agent, report, machine?.agents?.firstOrNull { it.agent == CloudShellInfo.key(agent) })
        Gap(12.dp)
    }
    if (report.partial) FinePrint("Cloud Shell took long to read every file: some numbers may be low. Read again.")
}

@Composable
private fun AgentCard(agent: Agent, report: UsageReport, status: AgentStatus?) {
    val context = LocalContext.current
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentLogo(agent, size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(agent.displayName, style = MaterialTheme.typography.titleMedium)
                facts(status)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        when (agent) {
            Agent.CLAUDE -> Tokens(report.claude, "/usage in Claude Code shows the plan's 5-hour and weekly limits, as does Claude's own page.")
            Agent.CODEX -> {
                Tokens(report.codex, "/status in Codex shows the plan's limits, as does its own page.")
                CodexLimits(report.codex, report.now)
            }
            Agent.ANTIGRAVITY -> {
                InfoRow("Chats in the last day", report.antigravity.chatsDay.toString())
                InfoRow("Chats in the last 7 days", "${report.antigravity.chats} (${report.antigravity.steps} steps)")
                Note("Google shows Antigravity's quota inside Antigravity itself; its plans say how much each one gives.")
            }
        }
        val (label, url) = usagePage(agent)
        OutlinedButton(onClick = { Browser.open(context, url) }) { Text(label) }
    }
}

/** "version 2.1.286 · VS Code running · signed in", or null when Cloud Shell said nothing of the agent. */
private fun facts(status: AgentStatus?): String? {
    status ?: return null
    return listOfNotNull(
        status.version?.let { "version $it" },
        if (status.running) "VS Code running" else "VS Code stopped",
        "signed in".takeIf { status.signedIn == true },
    ).joinToString(" · ")
}

/** Each company's own page of the plan's limits. */
private fun usagePage(agent: Agent): Pair<String, String> = when (agent) {
    Agent.CLAUDE -> "Claude's usage page" to DocLinks.CLAUDE_USAGE
    Agent.CODEX -> "Codex's usage page" to DocLinks.CODEX_USAGE
    Agent.ANTIGRAVITY -> "Antigravity's plans" to DocLinks.ANTIGRAVITY_PLANS
}

/** Codex's limits, as Codex last wrote them down (newer Codex versions may leave them out). */
@Composable
private fun CodexLimits(usage: AgentUsage, now: Long) {
    usage.limits.forEach { window ->
        val name = when (window.minutes) {
            FIVE_HOURS_MINUTES -> "5-hour limit"
            WEEK_MINUTES -> "Weekly limit"
            null -> "Limit"
            else -> "${Formats.minutes(window.minutes.toInt())} limit"
        }
        val resets = if (window.resetsAt > 0) ", resets ${Formats.until(window.resetsAt, now)}" else ""
        Meter("$name: ${window.usedPercent.toInt()}% used$resets", window.usedPercent / FULL_PERCENT)
    }
    if (usage.limits.isNotEmpty()) Note("As Codex last wrote them down, ${Formats.ago(usage.limitsAt, now)}.")
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Tokens(usage: AgentUsage, limits: String) {
    if (usage.error != null) {
        NoticeCard("Its files could not be read (${usage.error}).", tone = Tone.WARN)
        return
    }
    InfoRow("Last day", tokens(usage.day.total))
    InfoRow("Last 7 days", "${tokens(usage.week.total)} in ${usage.chats} chats")
    if (usage.week.total > 0) {
        Text(
            "Of the 7 days: ${tokens(usage.week.input)} in, ${tokens(usage.week.output)} out, ${tokens(usage.week.cacheRead)} " +
                "read again from the cache" + if (usage.week.cacheWrite > 0) ", ${tokens(usage.week.cacheWrite)} cached." else ".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Note(limits)
}

/** Tokens the way people say them: 950, 12.4k, 3.1M. */
internal fun tokens(count: Long): String = when {
    count >= MILLION -> String.format(Locale.ENGLISH, "%.1fM tokens", count / MILLION.toDouble())
    count >= THOUSAND -> String.format(Locale.ENGLISH, "%.1fk tokens", count / THOUSAND.toDouble())
    else -> "$count tokens"
}

/** A span the way people say it: 45 min, 3 h 05 min. */
internal fun duration(ms: Long): String {
    val minutes = ms / MINUTE_MS
    if (minutes < MINUTES_IN_HOUR) return "$minutes min"
    return String.format(Locale.ENGLISH, "%d h %02d min", minutes / MINUTES_IN_HOUR, minutes % MINUTES_IN_HOUR)
}

private fun share(part: Long, whole: Long): Double = part / whole.coerceAtLeast(1).toDouble()

private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val MINUTES_IN_HOUR = 60
private const val SESSION_MS = 12 * HOUR_MS
private const val WEEK_MS = 7 * 24 * HOUR_MS
private const val WEEKLY_HOURS = 50
private const val FIVE_HOURS_MINUTES = 300L
private const val WEEK_MINUTES = 10_080L
private const val FULL_PERCENT = 100.0
private const val THOUSAND = 1_000L
private const val MILLION = 1_000_000L
