package com.pocketide.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.sync.RestoreChoice
import com.pocketide.sync.RestorePlan
import com.pocketide.ui.components.InfoRow
import com.pocketide.ui.components.Tone
import com.pocketide.ui.manage.PlainError
import com.pocketide.ui.manage.attempt
import com.pocketide.ui.shell.CheckCard
import com.pocketide.ui.shell.CheckItem
import com.pocketide.ui.shell.Formats
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.OutlinedCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.SecondaryAction
import com.pocketide.ui.shell.SectionLabel
import com.pocketide.ui.shell.SettingChoices
import com.pocketide.ui.shell.rememberGraph
import kotlinx.coroutines.launch

/**
 * "Your chats from before" (§6.9): what a returning owner's Drive holds, what downloads now and
 * what waits until opened, over which connection, and "Bring my chats back". Shown while
 * [RestoreOffer.pending]: in the Computer step, and on Home after set-up.
 */
@Composable
fun RestorePlanPanel(modifier: Modifier = Modifier) {
    val graph = rememberGraph()
    val offer = rememberRestoreOffer()
    val network by rememberNetwork(LocalContext.current)
    var load by remember { mutableStateOf<PlanLoad>(PlanLoad.Loading) }
    var tries by remember { mutableIntStateOf(0) }
    LaunchedEffect(tries) {
        load = PlanLoad.Loading
        load = loadPlan(graph.sync::restorePlan).also { result ->
            if (result is PlanLoad.Loaded && result.plan.driveTotalBytes <= 0) offer.nothingInDrive()
        }
    }

    Column(modifier) {
        SectionLabel("Your chats from before")
        when (val current = load) {
            PlanLoad.Loading -> ProgressCard("Reading what your Drive holds…", null, null)
            is PlanLoad.Failed -> {
                NoticeCard(
                    title = "Couldn't read your Drive",
                    text = "${current.why.trimEnd('.')}. Your chats are safe in Drive; try again when you're online.",
                    tone = Tone.WARN,
                )
                Gap(12.dp)
                SecondaryAction("Try again", onClick = { tries++ })
            }
            is PlanLoad.Loaded ->
                if (current.plan.driveTotalBytes <= 0) {
                    CheckCard(listOf(CheckItem("Nothing to bring back", "Your Drive holds no chats from before.")))
                } else {
                    PlanChoice(current.plan, network, offer)
                }
        }
    }
}

@Composable
private fun PlanChoice(plan: RestorePlan, network: Network, offer: RestoreOffer) {
    val graph = rememberGraph()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val pending by offer.pending.collectAsStateWithLifecycle()
    var choiceName by rememberSaveable { mutableStateOf(RestoreChoice.WIFI_ONLY.name) }
    var error by remember { mutableStateOf<String?>(null) }
    val choice = RestoreChoice.valueOf(choiceName)
    val mobileAllowed = settings.mobileDailyLimitMb > 0

    OutlinedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow("In your Drive", Formats.bytes(plan.driveTotalBytes))
            InfoRow("Downloads now", "${Formats.bytes(plan.downloadNowBytes)} · ${plan.sessionsNow} chats")
            InfoRow("Stays in Drive until opened", "${Formats.bytes(plan.laterBytes)} · ${plan.sessionsLater} chats")
            InfoRow("Free on this phone", Formats.bytes(plan.freeBytes))
            InfoRow("Connection", network.label)
        }
    }
    Text(
        "Now: memory, settings, secrets and the last 30 days of chats. Older chats download when you open them.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (plan.downloadNowBytes > plan.freeBytes) {
        Gap(8.dp)
        NoticeCard("This phone doesn't have room for the first download. Free some space first.", Tone.ERROR)
    }
    if (!pending) {
        Gap(12.dp)
        CheckCard(listOf(CheckItem("Restoring in the background", "Your chats appear as they arrive.")))
        return
    }

    SectionLabel("Daily mobile data limit")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingChoices.dailyMobileLimitMb.forEach { option ->
            FilterChip(
                selected = settings.mobileDailyLimitMb == option.value,
                onClick = { graph.settings.update { it.copy(mobileDailyLimitMb = option.value) } },
                label = { Text(Formats.megabytes(option.value)) },
            )
        }
    }

    SectionLabel("Download over")
    Column(Modifier.selectableGroup()) {
        val wifiOnly = choice == RestoreChoice.WIFI_ONLY || !mobileAllowed
        RestoreOption("Wi-Fi only", "Waits for Wi-Fi if you're on mobile data.", wifiOnly, enabled = true) {
            choiceName = RestoreChoice.WIFI_ONLY.name
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        RestoreOption(
            title = "Mobile data up to ${Formats.megabytes(settings.mobileDailyLimitMb)} a day",
            detail = if (mobileAllowed) "The rest continues on Wi-Fi." else "Your daily limit is Off.",
            selected = choice == RestoreChoice.MOBILE_UP_TO_LIMIT && mobileAllowed,
            enabled = mobileAllowed,
        ) { choiceName = RestoreChoice.MOBILE_UP_TO_LIMIT.name }
    }
    Gap(12.dp)
    error?.let {
        NoticeCard(it, Tone.ERROR)
        Gap(8.dp)
    }
    PrimaryAction(
        "Bring my chats back",
        enabled = plan.downloadNowBytes <= plan.freeBytes,
        onClick = {
            offer.started()
            error = null
            val picked = if (mobileAllowed) choice else RestoreChoice.WIFI_ONLY
            // The app's scope: the restore goes on when this screen is left.
            graph.scope.launch {
                attempt { graph.sync.restore(picked) }.onFailure {
                    offer.failed()
                    error = PlainError.of(it)
                }
            }
        },
    )
}

private val Network.label: String
    get() = when (this) {
        Network.WIFI -> "Wi-Fi"
        Network.MOBILE -> "Mobile data"
        Network.OFFLINE -> "Offline"
    }

@Composable
private fun RestoreOption(title: String, detail: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
