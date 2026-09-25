package com.pocketide.ui.screens.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.agents.CandidateFacts
import com.pocketide.agents.DoctorReport
import com.pocketide.agents.mobileDataQuestion
import com.pocketide.core.Ist
import com.pocketide.docs.DocLinks
import com.pocketide.docs.DocsContent
import com.pocketide.model.AgentCandidate
import com.pocketide.model.AgentInfo
import com.pocketide.sync.NeedsMobileData
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import com.pocketide.ui.manage.ActionRunner
import com.pocketide.ui.manage.AgentTrust
import com.pocketide.ui.manage.ConfirmDialog
import com.pocketide.ui.manage.EmptyNote
import com.pocketide.ui.manage.Hint
import com.pocketide.ui.manage.LinkRow
import com.pocketide.ui.manage.ManageFormat
import com.pocketide.ui.manage.ManagePage
import com.pocketide.ui.manage.NavRow
import com.pocketide.ui.manage.PlainError
import com.pocketide.ui.manage.SectionLabel
import com.pocketide.ui.manage.ToneLine
import com.pocketide.ui.manage.Told
import com.pocketide.ui.manage.rememberActionRunner
import com.pocketide.ui.manage.rememberGraph
import com.pocketide.ui.nav.PocketNav
import java.util.Locale

private const val COMMUNITY_NOTE =
    "\"Verified\" on Open VSX means the publisher proved it owns this name. It is not a review of the code, and it does " +
        "not say who makes the AI model. If this company is not the model maker, your code goes to them and to the model " +
        "service they use."
private const val DATA_NOTE = "Your prompts and code will go to this publisher's service."

/**
 * The official three, the agents the owner added, and new ones found on Open VSX waiting for a
 * tap. Nothing is installed without that tap; "Only official agents" hides everything else.
 */
@Composable
fun MoreAgentsScreen(nav: PocketNav) {
    val graph = rememberGraph()
    val runner = rememberActionRunner()
    val installed by graph.agents.installed.collectAsStateWithLifecycle()
    val candidates by graph.agents.candidates.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val onlyOfficial = settings.onlyOfficialAgents
    var removing by remember { mutableStateOf<AgentInfo?>(null) }
    var report by remember { mutableStateOf<Pair<String, DoctorReport>?>(null) }
    var askMobileData by remember { mutableStateOf<Pair<AgentCandidate, NeedsMobileData>?>(null) }
    val official = installed.filter { it.official }
    val added = installed.filter { !it.official }

    fun add(candidate: AgentCandidate) {
        runner.run(
            key = "add:${candidate.extensionId}",
            onFailure = { error ->
                val question = mobileDataQuestion(error)
                if (question != null) askMobileData = candidate to question else runner.say(PlainError.of(error))
            },
            onSuccess = { result: DoctorReport -> report = candidate.displayName to result },
        ) { graph.agents.add(candidate) }
    }

    ManagePage("More agents", nav, runner) {
        item {
            SectionCard(null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Only official agents", style = MaterialTheme.typography.titleMedium)
                        Hint("Shows only Claude Code, Codex and Antigravity. Turn on once if you want no other company involved.")
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = onlyOfficial,
                        onCheckedChange = { on -> graph.settings.update { it.copy(onlyOfficialAgents = on) } },
                    )
                }
            }
        }
        item { SectionLabel("Official") }
        items(official, key = { it.id }) { agent -> AgentCard(agent, nav, onRemove = null) }
        if (!onlyOfficial) {
            if (added.isNotEmpty()) {
                item { SectionLabel("Added by you") }
                items(added, key = { it.id }) { agent -> AgentCard(agent, nav, onRemove = { removing = agent }) }
            }
            item { NewHeader(runner) { runner.run("discover", done = "Checked Open VSX for new agents.") { graph.agents.discover() } } }
            val fresh = candidates.filter { c -> installed.none { it.extensionId.equals(c.extensionId, ignoreCase = true) } }
            if (fresh.isEmpty()) {
                item {
                    EmptyNote(
                        Icons.Outlined.Verified,
                        "Nothing new",
                        "Once a week the app looks on Open VSX for popular AI agents from verified publishers. New ones appear here.",
                    )
                }
            }
            items(fresh, key = { it.extensionId }) { candidate ->
                CandidateCard(candidate, graph.agents.facts(candidate.extensionId), runner, nav) { add(candidate) }
            }
        } else if (added.isNotEmpty()) {
            item { Hint("${ManageFormat.count(added.size, "agent")} you added ${if (added.size == 1) "is" else "are"} hidden while this is on.") }
        }
        item { SectionLabel("Where agents come from") }
        item {
            SectionCard(null) {
                Hint(
                    "Agents come only from Open VSX, the open extension registry that Cursor, VSCodium, Windsurf and Antigravity's editor " +
                        "also use. Microsoft's VS Code Marketplace may be used only by Microsoft's own products, so an extension " +
                        "published only there does not appear here until its publisher also puts it on Open VSX.",
                )
                Hint(
                    "An agent is offered only when its publisher is verified, it has an arm64 build, at least 50,000 downloads, " +
                        "was first published at least 14 days ago, and has an agent screen the app can show full screen.",
                )
            }
        }
    }

    removing?.let { agent ->
        ConfirmDialog(
            title = "Remove ${agent.displayName}?",
            text = "First its sessions' work is pushed to GitHub and its chats are backed up to Drive. If something cannot be " +
                "saved, nothing is removed. Then its room on this phone is deleted, with its sign-in. Its chats stay in " +
                "your Drive until you delete them.",
            confirmLabel = "Remove",
            destructive = true,
            // Saving and deleting take a while: leaving the screen must not stop it half way.
            onConfirm = { runner.run("remove:${agent.id}", done = "${agent.displayName} removed.", outlivesScreen = true) { graph.agents.remove(agent.id) } },
            onDismiss = { removing = null },
        )
    }
    report?.let { (name, result) -> DoctorDialog(name, result) { report = null } }
    askMobileData?.let { (candidate, question) ->
        AlertDialog(
            onDismissRequest = { askMobileData = null },
            title = { Text("Download ${question.size} on mobile data?") },
            text = { Text("${candidate.displayName} is big, so it waits for Wi-Fi. It can download now on mobile data instead.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        askMobileData = null
                        graph.dataBudget.allowOnce(question.kind, question.bytes)
                        add(candidate)
                    },
                ) { Text("Use mobile data") }
            },
            dismissButton = { TextButton(onClick = { askMobileData = null }) { Text("Wait for Wi-Fi") } },
        )
    }
}

@Composable
private fun AgentCard(agent: AgentInfo, nav: PocketNav, onRemove: (() -> Unit)?) {
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(agent.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Hint(listOfNotNull(agent.publisher, agent.version?.let { "version $it" }).joinToString(" · "))
            }
            Label(agent.official, agent.verifiedPublisher)
        }
        Text(agent.dataGoesTo, style = MaterialTheme.typography.bodyMedium)
        Hint(agent.signIn)
        agent.communityNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = toneColor(Tone.WARN)) }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                NavRow(Icons.AutoMirrored.Outlined.MenuBook, "About this agent", null) { nav.help(DocsContent.agentPage(agent).id) }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, contentDescription = "Remove ${agent.displayName}") }
            }
        }
    }
}

@Composable
private fun Label(official: Boolean, verified: Boolean) {
    when {
        official -> StatusChip("Official", Tone.OK)
        verified -> StatusChip("Verified publisher", Tone.NEUTRAL)
        else -> StatusChip("Not verified", Tone.WARN)
    }
}

@Composable
private fun NewHeader(runner: ActionRunner, onCheck: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("New", Modifier.weight(1f))
        TextButton(onClick = onCheck, enabled = !runner.isBusy("discover")) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (runner.isBusy("discover")) "Checking…" else "Check now")
        }
    }
}

@Composable
private fun CandidateCard(candidate: AgentCandidate, facts: CandidateFacts?, runner: ActionRunner, nav: PocketNav, onAdd: () -> Unit) {
    val busy = runner.isBusy("add:${candidate.extensionId}")
    val problem = AgentTrust.problem(candidate.extensionId)
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    candidate.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Hint(candidate.publisher)
            }
            StatusChip("Verified publisher", Tone.NEUTRAL)
        }
        // The identifier exactly as the publisher wrote it, so a look-alike name can be read letter by letter.
        Text(facts?.identifier ?: candidate.extensionId, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        if (candidate.description.isNotBlank()) {
            Text(candidate.description, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Hint(
            "${ManageFormat.downloads(candidate.downloads)} downloads · version ${candidate.version} · " +
                "first published ${Ist.date(candidate.firstPublishedAt)}",
        )
        facts?.let { CandidateFactLines(it) }
        Text(DATA_NOTE, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(COMMUNITY_NOTE, style = MaterialTheme.typography.bodyMedium, color = toneColor(Tone.WARN))
        DocLinks.openVsxPage(candidate.extensionId)?.let { LinkRow("Its page on Open VSX", it, nav, note = "Licence, source and reviews") }
        if (problem != null) {
            ToneLine(Told("Not offered: $problem", Tone.ERROR))
        } else {
            Hint("Adding tests it on this phone and gives it its own room. It uses memory and storage; you can remove it any time.")
            Button(onClick = onAdd, enabled = !busy) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "Adding and testing…" else "Add", maxLines = 1)
            }
        }
    }
}

/** Licence, source and reviews from Open VSX, and what "verified" does and does not mean. */
@Composable
private fun CandidateFactLines(facts: CandidateFacts) {
    Hint("Licence: ${facts.license?.takeIf { it.isNotBlank() } ?: "not given"}")
    Hint(facts.repository?.let { "Source: $it" } ?: "Source: closed source (no repository given)")
    val rating = facts.averageRating
    Hint(
        if (rating == null || facts.reviewCount == 0L) {
            "No reviews yet"
        } else {
            "Rated %.1f of 5 in ${ManageFormat.count(facts.reviewCount.toInt(), "review")}".format(Locale.ENGLISH, rating)
        },
    )
    Hint(CandidateFacts.VERIFIED_MEANS)
}

@Composable
private fun DoctorDialog(name: String, report: DoctorReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (report.ok) "$name works on this phone" else "$name did not pass the test") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                report.checks.forEach { (check, passed) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (passed) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                            contentDescription = if (passed) "Passed" else "Failed",
                            tint = toneColor(if (passed) Tone.OK else Tone.ERROR),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(check, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                report.note?.let { Hint(it) }
            }
        },
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text("OK") } },
    )
}
