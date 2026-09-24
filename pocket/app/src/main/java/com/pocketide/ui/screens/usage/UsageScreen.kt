package com.pocketide.ui.screens.usage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.core.Ist
import com.pocketide.github.AccountUsage
import com.pocketide.github.RepoUsage
import com.pocketide.google.DriveQuota
import com.pocketide.model.Project
import com.pocketide.ui.components.InfoRow
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.manage.ActionsUsage
import com.pocketide.ui.manage.AsOfLine
import com.pocketide.ui.manage.ErrorNote
import com.pocketide.ui.manage.Hint
import com.pocketide.ui.manage.LinkRow
import com.pocketide.ui.manage.LoadState
import com.pocketide.ui.manage.ManageFormat
import com.pocketide.ui.manage.ManagePage
import com.pocketide.ui.manage.RepoSize
import com.pocketide.ui.manage.SectionLabel
import com.pocketide.ui.manage.UsageMeter
import com.pocketide.ui.manage.rememberGraph
import com.pocketide.ui.manage.rememberLoad
import com.pocketide.ui.nav.PocketNav
import java.util.Locale

private const val GITHUB_BILLING = "https://github.com/settings/billing"
private const val GOOGLE_STORAGE = "https://one.google.com/storage"

/**
 * Live usage, never frozen numbers: GitHub Actions minutes and storage against the owner's
 * plan, each project repository, an estimate of builds left, and Google storage. Every block
 * says when it was read and can be refreshed.
 */
@Composable
fun UsageScreen(nav: PocketNav) {
    val graph = rememberGraph()
    val projects by graph.projects.all.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val now = graph.clock::now
    val github = rememberLoad("github", now) { graph.usage.github() }
    val estimate = rememberLoad("estimate", now) { graph.usage.estimate() }
    val google = rememberLoad("google", now) { graph.usage.google() }

    ManagePage("Usage", nav) {
        item { SectionLabel("GitHub Actions") }
        item {
            SectionCard(null) {
                AsOfLine(github.at, github.loading) {
                    github.refresh()
                    estimate.refresh()
                }
                github.error?.let { ErrorNote(it) }
                val usage = github.value
                if (usage != null) ActionsBlock(usage, graph.clock.now()) else if (!github.loading && github.error == null) {
                    Hint("GitHub did not report usage for this account. Your billing page has it.")
                }
                estimate.value?.let { e ->
                    val parts = listOfNotNull(
                        e.androidLeft?.let { "about $it Android" },
                        e.iosLeft?.let { "about $it iOS" },
                    )
                    if (parts.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Builds left this month: ${parts.joinToString(" and ")}", style = MaterialTheme.typography.bodyLarge)
                        if (e.basis.isNotBlank()) Hint(e.basis)
                    }
                }
                LinkRow("Billing and plans on GitHub", GITHUB_BILLING, nav)
            }
        }
        item { SectionLabel("Project repositories") }
        if (projects.isEmpty()) {
            item { SectionCard(null) { Hint("No projects yet.") } }
        }
        items(projects, key = { it.id }) { project -> RepoCard(project) }
        item { SectionLabel("Google storage") }
        item { GoogleCard(google, settings.driveLimitGb, nav) }
    }
}

@Composable
private fun ActionsBlock(usage: AccountUsage, nowMs: Long) {
    val summary = ActionsUsage.summarize(usage)
    summary.plan?.let { InfoRow("Plan", it.replaceFirstChar { c -> c.uppercase() }) }
    val allowance = summary.allowance
    val counted = summary.countedMinutes
    if (allowance != null) {
        InfoRow("Minutes used", "${ManageFormat.minutes(counted)} of ${ManageFormat.minutes(allowance.minutes.toDouble())}")
        UsageMeter(counted / allowance.minutes)
    } else {
        InfoRow("Minutes used", ManageFormat.minutes(counted))
    }
    if (summary.byOs.isEmpty()) Hint("No Actions minutes used in private repositories this month.")
    summary.byOs.forEach { os ->
        val extra = if (os.os.multiplier > 1) " × ${os.os.multiplier} = ${ManageFormat.minutes(os.counted)}" else ""
        InfoRow(os.os.label, ManageFormat.minutes(os.minutes) + extra)
    }
    Hint("Linux counts 1×, Windows 2× and macOS 10× against the included minutes. Public repositories are free.")
    val share = ActionsUsage.storageShare(summary.storageGbHours, allowance, nowMs)
    if (allowance != null) {
        InfoRow(
            "Artifact storage",
            (share?.let { ManageFormat.percentText(it, 1.0) + " of " } ?: "") + ManageFormat.bytes(allowance.artifactStorageBytes) + " included",
        )
        if (share != null) UsageMeter(share)
    } else if (summary.storageGbHours > 0) {
        InfoRow("Artifact storage", String.format(Locale.ENGLISH, "%.1f GB-hours", summary.storageGbHours))
    }
    InfoRow("Charged this month", if (summary.chargedUsd > 0) ManageFormat.usd(summary.chargedUsd) else "Nothing")
    InfoRow("Resets", Ist.date(ActionsUsage.resetAt(nowMs)))
    if (allowance != null) Hint("Included amounts from GitHub's plan table as of ${ActionsUsage.ALLOWANCE_AS_OF}.")
}

@Composable
private fun RepoCard(project: Project) {
    val graph = rememberGraph()
    val load: LoadState<RepoUsage?> = rememberLoad(project.id, graph.clock::now) { graph.usage.repo(project.id) }
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(project.repo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Hint(project.owner)
            }
            val private = load.value?.repo?.isPrivate ?: project.isPrivate
            StatusChip(if (private) "Private" else "Public", if (private) Tone.NEUTRAL else Tone.OK)
        }
        AsOfLine(load.at, load.loading, load::refresh)
        load.error?.let { ErrorNote(it) }
        val usage = load.value
        if (usage == null) {
            if (!load.loading && load.error == null) Hint("GitHub did not report this repository.")
            return@SectionCard
        }
        val size = RepoSize.bytes(usage.repo.sizeKb)
        InfoRow("Size", "${ManageFormat.bytes(size)} of ${ManageFormat.bytes(RepoSize.GUIDELINE_BYTES)} recommended")
        UsageMeter(size.toDouble() / RepoSize.GUIDELINE_BYTES)
        InfoRow("Actions cache", ManageFormat.bytes(usage.cacheBytes))
        InfoRow("Artifacts", "${ManageFormat.count(usage.artifactCount, "file")} · ${ManageFormat.bytes(usage.artifactsBytes)}")
        Hint(
            if (usage.repo.isPrivate) "Private: builds use your included minutes." else "Public: builds on standard runners are free, and anyone can see the code.",
        )
    }
}

@Composable
private fun GoogleCard(google: LoadState<DriveQuota?>, driveLimitGb: Int, nav: PocketNav) {
    SectionCard(null) {
        AsOfLine(google.at, google.loading, google::refresh)
        google.error?.let { ErrorNote(it) }
        val quota = google.value
        if (quota == null && google.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (quota != null) {
            quota.email?.let { InfoRow("Account", it) }
            val limit = quota.limitBytes
            if (limit != null && limit > 0) {
                InfoRow("Used", "${ManageFormat.bytes(quota.usageBytes)} of ${ManageFormat.bytes(limit)}")
                UsageMeter(quota.usageBytes.toDouble() / limit)
            } else {
                InfoRow("Used", "${ManageFormat.bytes(quota.usageBytes)} (no limit)")
            }
            InfoRow("In Drive", ManageFormat.bytes(quota.usageInDriveBytes))
            val share = driveLimitGb * 1_000_000_000L
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoRow("PocketIDE", "${ManageFormat.bytes(quota.appDataBytes)} of your $driveLimitGb GB limit")
                if (share > 0) UsageMeter(quota.appDataBytes.toDouble() / share)
            }
            Hint("Gmail, Photos and Drive share this storage. PocketIDE's part is its hidden, encrypted folder.")
        }
        LinkRow("See what uses your Google storage", GOOGLE_STORAGE, nav)
    }
}
