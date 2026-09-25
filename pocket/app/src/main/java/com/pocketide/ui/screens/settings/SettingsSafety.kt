package com.pocketide.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.AppGraph
import com.pocketide.core.Settings
import com.pocketide.secrets.SecretKind
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import com.pocketide.ui.nav.PocketNav
import com.pocketide.ui.shell.CheckLine
import com.pocketide.ui.shell.CheckStatus
import com.pocketide.ui.shell.External
import com.pocketide.ui.shell.FinePrint
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.Links
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.OutlinedCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.PrivacyChecklist
import com.pocketide.ui.shell.PrivacyChecklistCard
import com.pocketide.ui.shell.ReconnectGitHubDialog
import com.pocketide.ui.shell.SafetyCheck
import com.pocketide.ui.shell.SafetyFacts
import com.pocketide.ui.shell.SafetyFix
import com.pocketide.ui.shell.SectionLabel
import com.pocketide.ui.shell.saveKeyNow

/**
 * The standing safety check: what protects the owner's data right now, each problem with its
 * fix. Read from what the phone already knows; nothing is fetched to draw it.
 */
@Composable
internal fun SafetySection(
    graph: AppGraph,
    settings: Settings,
    nav: PocketNav,
    update: ((Settings) -> Settings) -> Unit,
    onOpenPrivacyChecklist: () -> Unit,
) {
    val context = LocalContext.current
    val key by graph.vault.state.collectAsStateWithLifecycle()
    val keyNotice by graph.vault.notice.collectAsStateWithLifecycle()
    val values by graph.secrets.values.collectAsStateWithLifecycle()
    val account by graph.gitHubAuth.account.collectAsStateWithLifecycle()
    var reconnecting by rememberSaveable { mutableStateOf(false) }
    if (reconnecting) ReconnectGitHubDialog(onDismiss = { reconnecting = false })
    // The owner may add a screen lock in Android's settings and come back.
    var screenLock by remember { mutableStateOf(graph.appLock.deviceSecure()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { screenLock = graph.appLock.deviceSecure() }
    var notice by remember { mutableStateOf<String?>(null) }

    val facts = SafetyFacts(
        screenLock = screenLock,
        appLock = settings.appLock,
        privacyChecklistDone = settings.privacyChecklistDone,
        key = key,
        keyNotice = keyNotice,
        onlyOfficialAgents = settings.onlyOfficialAgents,
        variables = values.filter { it.kind == SecretKind.VARIABLE }.map { it.projectId to it.name },
        gitHubConnected = account != null,
    )
    val lines = SafetyCheck.lines(facts)
    val problems = lines.count { it.status == CheckStatus.PROBLEM }

    SectionLabel("Safety check")
    OutlinedCard {
        Text(
            if (problems == 0) "Everything here is as it should be." else "$problems ${if (problems == 1) "thing needs" else "things need"} you.",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        )
        lines.forEach { line ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SafetyRow(line) {
                notice = null
                when (line.fix) {
                    SafetyFix.SCREEN_LOCK -> External.openSecuritySettings(context)
                    SafetyFix.APP_LOCK -> if (screenLock) update { it.copy(appLock = true) } else notice = "Set a screen lock on the phone first."
                    SafetyFix.PRIVACY_CHECKLIST -> onOpenPrivacyChecklist()
                    SafetyFix.VARIABLES -> nav.secrets(facts.variables.firstOrNull { SafetyCheck.looksSecret(it.second) }?.first)
                    SafetyFix.ONLY_OFFICIAL -> update { it.copy(onlyOfficialAgents = true) }
                    SafetyFix.RECONNECT_GITHUB -> reconnecting = true
                    SafetyFix.SAVE_KEY_NOW -> saveKeyNow(graph)
                    null -> Unit
                }
            }
        }
    }
    notice?.let {
        Gap(8.dp)
        NoticeCard(it, Tone.WARN)
    }
}

@Composable
private fun SafetyRow(line: CheckLine, onFix: () -> Unit) {
    val (tone, icon, spoken) = when (line.status) {
        CheckStatus.DONE -> Triple(Tone.OK, Icons.Outlined.Check, "Done")
        CheckStatus.PROBLEM -> Triple(Tone.WARN, Icons.Outlined.WarningAmber, "Needs attention")
        CheckStatus.WAITING -> Triple(Tone.NEUTRAL, Icons.Outlined.Schedule, "Not yet")
        CheckStatus.INFO -> Triple(Tone.NEUTRAL, Icons.Outlined.Info, null)
    }
    val color = toneColor(tone)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.semantics(mergeDescendants = true) { spoken?.let { stateDescription = it } }, verticalAlignment = Alignment.Top) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(line.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(line.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (line.fix != null && line.fixLabel != null) {
            TextButton(onClick = onFix, modifier = Modifier.padding(start = 34.dp)) { Text(line.fixLabel) }
        }
    }
}

/**
 * The privacy checklist, kept here after set-up: each page opens in Chrome; until it was
 * finished, the rows can be ticked and marked done.
 */
@Composable
internal fun PrivacySection(nav: PocketNav, settings: Settings, update: ((Settings) -> Settings) -> Unit, modifier: Modifier = Modifier) {
    var ticked by rememberSaveable { mutableStateOf("") }
    val done = ticked.split(',').filter { it.isNotEmpty() }.toSet()
    Column(modifier) {
        SectionLabel("Privacy checklist")
        if (settings.privacyChecklistDone) {
            SettingsGroup(
                Links.privacyChecklist.map { link ->
                    @Composable {
                        ActionRow(link.title, link.what, onClick = { nav.openExternal(link.url) }, external = true, icon = Icons.Outlined.PrivacyTip)
                    }
                },
            )
        } else {
            NoticeCard("Not finished yet. Open each page, switch it, then tick it here.", Tone.WARN)
            Gap(12.dp)
            PrivacyChecklistCard(
                checked = done,
                onToggle = { id, on -> ticked = (if (on) done + id else done - id).joinToString(",") },
                onOpen = { nav.openExternal(it.url) },
            )
            Gap(12.dp)
            PrimaryAction(
                "Mark as done",
                enabled = PrivacyChecklist.complete(done),
                onClick = { update { it.copy(privacyChecklistDone = true) } },
            )
            FinePrint("The Copilot and device-code rows are optional.")
        }
    }
}
