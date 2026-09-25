package com.pocketide.ui.screens.computer

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.pocketide.AppGraph
import com.pocketide.core.Ist
import com.pocketide.linux.ComputerInfo
import com.pocketide.linux.ComputerState
import com.pocketide.linux.RepairItem
import com.pocketide.linux.RepairStatus
import com.pocketide.model.PhoneSnapshot
import com.pocketide.rooms.RoomState
import com.pocketide.ui.components.InfoRow
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.manage.ComputerExpiry
import com.pocketide.ui.manage.ConfirmDialog
import com.pocketide.ui.manage.ErrorNote
import com.pocketide.ui.manage.Hint
import com.pocketide.ui.manage.ManageFormat
import com.pocketide.ui.manage.ManagePage
import com.pocketide.ui.manage.ManageText
import com.pocketide.ui.manage.PhoneFacts
import com.pocketide.ui.manage.PhoneRequirements
import com.pocketide.ui.manage.PlainError
import com.pocketide.ui.manage.RequirementCheck
import com.pocketide.ui.manage.ToneLine
import com.pocketide.ui.manage.Told
import com.pocketide.ui.manage.UsageMeter
import com.pocketide.ui.manage.attempt
import com.pocketide.ui.manage.rememberActionRunner
import com.pocketide.ui.manage.rememberGraph
import com.pocketide.ui.manage.rememberLoad
import com.pocketide.ui.nav.PocketNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone and the Linux computer inside the app, as 2.6.0 showed it: processor, memory,
 * storage, versions of everything, each room's live memory, and whether this phone meets the
 * requirements. "Reset computer" rebuilds it; nothing of the owner's lives only there.
 */
@Composable
fun ComputerScreen(nav: PocketNav) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val runner = rememberActionRunner()
    val snapshot by graph.phone.snapshot.collectAsStateWithLifecycle()
    val state by graph.computer.state.collectAsStateWithLifecycle()
    val rooms by graph.rooms.states.collectAsStateWithLifecycle()
    val agents by graph.agents.installed.collectAsStateWithLifecycle()
    val sessions by graph.sessions.all.collectAsStateWithLifecycle()
    val projects by graph.projects.all.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val removalAt by graph.sync.computerRemovalAt.collectAsStateWithLifecycle()
    val info = rememberLoad(Unit, graph.clock::now) { withContext(Dispatchers.IO) { graph.computer.info() } }
    val size = rememberLoad(state::class, graph.clock::now) { withContext(Dispatchers.IO) { graph.computer.sizeBytes() } }
    val facts = rememberLoad(snapshot.storageFreeBytes / 1_000_000_000L, graph.clock::now) {
        withContext(Dispatchers.IO) { readFacts(context, graph, snapshot) }
    }
    val lastWork = ComputerExpiry.lastWork(sessions.map { it.lastActivityAt }, projects.map { it.lastActivityAt })
    val daysLeft = ComputerExpiry.daysLeft(lastWork, settings.computerUnusedDays, graph.clock.now())
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var confirmRestart by rememberSaveable { mutableStateOf(false) }
    var repair by remember { mutableStateOf<List<RepairItem>?>(null) }
    val working = runner.isBusy(RESET) || runner.isBusy(REPAIR) || state is ComputerState.Installing

    LaunchedEffect(Unit) { attempt { graph.phone.refresh() } }

    ManagePage("Computer", nav, runner) {
        item { StateCard(state, size.value, daysLeft, removalAt) }
        item { PhoneCard(snapshot, info.value) }
        item { VersionsCard(info.value, info.error, agents.map { it.displayName to it.version }) }
        item {
            SectionCard("Rooms") {
                if (agents.isEmpty()) Hint("No agents yet.")
                agents.forEach { agent ->
                    val told = ManageText.room(rooms[agent.id] ?: RoomState.Stopped, ManageFormat::bytes)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(agent.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        StatusChip(told.text, told.tone)
                    }
                }
                val running = rooms.values.filterIsInstance<RoomState.Running>().sumOf { it.memoryBytes }
                if (running > 0) Hint("Rooms use ${ManageFormat.bytes(running)} of memory now.")
            }
        }
        facts.value?.let { phoneFacts ->
            item { RequirementsCard(PhoneRequirements.check(phoneFacts)) }
        }
        item { NetworkPanel(runner) }
        item {
            FixLadder(
                working = working,
                onRestart = { confirmRestart = true },
                repair = repair,
                onRepair = {
                    runner.run(REPAIR, outlivesScreen = true, onSuccess = { items: List<RepairItem> ->
                        repair = items
                        runner.say(RepairText.summary(items))
                    }) {
                        val computerItems = graph.computer.repair()
                        computerItems + agents.map { agent ->
                            attempt { graph.agents.ensureInstalled(agent.id) }.fold(
                                { RepairItem(agent.displayName, RepairStatus.OK, "Installed and up to date.") },
                                { RepairItem(agent.displayName, RepairStatus.WARN, PlainError.of(it)) },
                            )
                        }
                    }
                },
                onReset = { confirmReset = true },
                onGuide = { nav.help(IF_SOMETHING_BREAKS) },
            )
        }
    }

    if (confirmRestart) {
        ConfirmDialog(
            title = "Restart the computer?",
            text = "Every room closes and its programs end. Files, sign-ins and chat history stay. Open an agent again to start it.",
            confirmLabel = "Restart",
            destructive = false,
            onConfirm = {
                runner.run(RESTART, done = "The computer restarted. Open an agent to start it.") {
                    graph.rooms.stopAll()
                    graph.computer.restart()
                }
            },
            onDismiss = { confirmRestart = false },
        )
    }
    if (confirmReset) {
        ResetComputerDialogs(graph, onClose = { confirmReset = false }, onNotice = { text, _ -> runner.say(text) })
    }
}

private const val RESET = RESET_KEY
private const val REPAIR = "repair"
private const val RESTART = "restart"

/** The guide section on fixing problems (docs/GuidePhone.kt). */
private const val IF_SOMETHING_BREAKS = "if-something-breaks"

private enum class Fix { RESTART, REPAIR, RESET }

/** One level of the fix-it ladder: what it fixes, what it costs, and the button this screen has for it. */
private data class Rung(val title: String, val text: String, val fix: Fix? = null)

private val ladder = listOf(
    Rung("1. Reload the agent screen", "In the agent's menu. Takes seconds; nothing stops."),
    Rung("2. Restart the agent", "In the agent's menu. Its engine starts again; the chat is kept."),
    Rung("3. Restart the computer", "Every room closes. Files, sign-ins and history stay.", Fix.RESTART),
    Rung("4. Repair", "Installs what is missing and updates what is there. Safe to run again; your files are not touched.", Fix.REPAIR),
    Rung("5. Reset computer", "Builds the computer again from scratch. Nothing of yours is lost, but it is a big download.", Fix.RESET),
)

/** Try each level only if the one above did not help. */
@Composable
private fun FixLadder(
    working: Boolean,
    repair: List<RepairItem>?,
    onRestart: () -> Unit,
    onRepair: () -> Unit,
    onReset: () -> Unit,
    onGuide: () -> Unit,
) {
    SectionCard("If something is wrong") {
        Hint("Start at the top. Go down a level only if the one above did not help.")
        TextButton(onClick = onGuide) { Text("What to try when something breaks") }
        ladder.forEach { rung ->
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(rung.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Hint(rung.text)
                when (rung.fix) {
                    Fix.RESTART -> TextButton(onClick = onRestart, enabled = !working) { Text("Restart computer") }
                    Fix.REPAIR -> {
                        TextButton(onClick = onRepair, enabled = !working) { Text("Repair") }
                        repair?.let { RepairReport(it) }
                    }
                    Fix.RESET -> OutlinedButton(onClick = onReset, enabled = !working) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Reset computer")
                    }
                    null -> Unit
                }
            }
        }
    }
}

/** Each item Repair looked at, problems first. */
@Composable
private fun RepairReport(items: List<RepairItem>) {
    if (items.isEmpty()) {
        Hint("Nothing needed repair.")
        return
    }
    RepairText.ordered(items).forEach { item ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(item.what, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            val told = RepairText.chip(item.status)
            StatusChip(told.text, told.tone)
        }
        if (item.detail.isNotBlank()) Hint(item.detail)
    }
}

@Composable
private fun StateCard(state: ComputerState, sizeBytes: Long?, daysLeft: Int?, removalAt: Long?) {
    SectionCard("Ubuntu computer") {
        if (daysLeft != null && state !is ComputerState.NotInstalled) {
            StatusChip(ComputerExpiry.chip(daysLeft), if (daysLeft <= 7) Tone.WARN else Tone.NEUTRAL)
        }
        if (removalAt != null && state !is ComputerState.NotInstalled) {
            ToneLine(Told("Removed on ${Ist.date(removalAt)} unless an agent runs. Your projects and chats stay.", Tone.WARN))
        }
        when (state) {
            ComputerState.NotInstalled -> ToneLine(Told("Not set up yet.", Tone.NEUTRAL))
            ComputerState.Ready -> ToneLine(Told("Ready.", Tone.OK))
            is ComputerState.Updating -> ToneLine(Told("Updating ${state.what}…", Tone.WARN))
            is ComputerState.Broken -> {
                ErrorNote(state.why)
                Hint(state.fix)
            }
            is ComputerState.Installing -> {
                ToneLine(Told(state.step, Tone.WARN))
                val fraction = state.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (state.bytesTotal > 0) Hint("${ManageFormat.bytes(state.bytesDone)} of ${ManageFormat.bytes(state.bytesTotal)}")
            }
        }
        if (sizeBytes != null && sizeBytes > 0) InfoRow("Size on this phone", ManageFormat.bytes(sizeBytes))
    }
}

@Composable
private fun PhoneCard(s: PhoneSnapshot, info: ComputerInfo?) {
    SectionCard("This phone") {
        InfoRow("Model", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
        val cpu = info?.cpu?.takeIf { it.isNotBlank() } ?: socModel()
        if (cpu != null) InfoRow("Processor", cpu)
        val cores = info?.cores?.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()
        InfoRow("Cores", cores.toString())
        if (s.totalRamBytes > 0) {
            InfoRow("Memory", "${ManageFormat.bytes(s.availRamBytes)} free of ${ManageFormat.bytes(s.totalRamBytes)}")
            UsageMeter(1.0 - s.availRamBytes.toDouble() / s.totalRamBytes)
        }
        if (s.storageTotalBytes > 0) {
            InfoRow("Storage", "${ManageFormat.bytes(s.storageFreeBytes)} free of ${ManageFormat.bytes(s.storageTotalBytes)}")
            UsageMeter(1.0 - s.storageFreeBytes.toDouble() / s.storageTotalBytes)
        }
        if (s.appDataBytes > 0) InfoRow("PocketIDE uses", ManageFormat.bytes(s.appDataBytes))
        InfoRow("Android", info?.android?.takeIf { it.isNotBlank() } ?: "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        info?.kernel?.takeIf { it.isNotBlank() }?.let { InfoRow("Kernel", it) }
        info?.vaBits?.let { InfoRow("Address space", "$it-bit") }
    }
}

@Composable
private fun VersionsCard(info: ComputerInfo?, error: String?, agents: List<Pair<String, String?>>) {
    SectionCard("Versions") {
        if (error != null && info == null) ErrorNote(error)
        InfoRow("Ubuntu", info?.ubuntu ?: notInstalled(info))
        InfoRow("Engine (code-server)", info?.codeServer ?: notInstalled(info))
        InfoRow("Antigravity hub (agy)", info?.agy ?: notInstalled(info))
        if (agents.isNotEmpty()) HorizontalDivider()
        agents.forEach { (name, version) -> InfoRow(name, version ?: "Not installed yet") }
    }
}

private fun socModel(): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null

private fun notInstalled(info: ComputerInfo?) = if (info == null) "…" else "Not installed"

@Composable
private fun RequirementsCard(checks: List<RequirementCheck>) {
    SectionCard("Requirements") {
        ToneLine(PhoneRequirements.summary(checks))
        checks.forEach { check ->
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(check.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    StatusChip(check.thisPhone, check.tone)
                }
                Hint("Minimum: ${check.minimum} · Recommended: ${check.recommended}")
            }
        }
    }
}

private fun readFacts(context: Context, graph: AppGraph, s: PhoneSnapshot): PhoneFacts {
    val memory = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
    val free = if (s.storageFreeBytes > 0) s.storageFreeBytes else runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
    return PhoneFacts(
        androidSdk = Build.VERSION.SDK_INT,
        androidRelease = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
        arm64 = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a"),
        playServices = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrNull(),
        totalRamBytes = if (s.totalRamBytes > 0) s.totalRamBytes else memory.totalMem,
        freeStorageBytes = free,
        screenLock = runCatching { graph.appLock.deviceSecure() }.getOrNull(),
    )
}
