package com.pocketide.ui.screens.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.AppGraph
import com.pocketide.BuildConfig
import com.pocketide.core.Redact
import com.pocketide.core.Settings
import com.pocketide.sync.DataUsage
import com.pocketide.ui.components.InfoRow
import com.pocketide.ui.components.Tone
import com.pocketide.ui.nav.PocketNav
import com.pocketide.ui.shell.ContentMaxWidth
import com.pocketide.ui.shell.Formats
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.Links
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.OutlinedCard
import com.pocketide.ui.shell.SectionLabel
import com.pocketide.ui.shell.SettingChoices
import com.pocketide.ui.shell.rememberGraph
import com.pocketide.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The few settings PocketIDE has (§4, §6.7, §6.8). Every change goes through
 * `graph.settings.update`, so each module sees it at once and the synced ones travel with the vault.
 */
@Composable
fun SettingsScreen(nav: PocketNav) {
    val graph = rememberGraph()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    fun update(change: (Settings) -> Settings) = graph.settings.update(change)

    val scope = rememberCoroutineScope()
    val privacyChecklist = remember { BringIntoViewRequester() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            SafetySection(graph, settings, nav, ::update, onOpenPrivacyChecklist = { scope.launch { privacyChecklist.bringIntoView() } })
            MobileDataSection(graph, settings, ::update)
            StorageSection(graph, settings, ::update)
            AgentsSection(settings, ::update)
            SecuritySection(graph, settings, ::update)
            ManageDataSection(nav, settings, ::update)
            PrivacySection(nav, settings, ::update, Modifier.bringIntoViewRequester(privacyChecklist))
            AdvancedSection(graph, settings)
            AccountsSection(graph, nav)
            AboutSection(graph, nav)
        }
    }
}

@Composable
private fun MobileDataSection(graph: AppGraph, settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    val usage by graph.sync.usage.collectAsStateWithLifecycle()
    SectionLabel("Mobile data")
    SettingsGroup(
        listOf(
            {
                ChoiceRow(
                    "Daily limit",
                    SettingChoices.dailyMobileLimitMb,
                    settings.mobileDailyLimitMb,
                    onPick = { mb -> update { it.copy(mobileDailyLimitMb = mb) } },
                    why = "Only mobile data counts. Once today's share is used, sync waits for Wi-Fi or tomorrow.",
                    fallbackLabel = Formats::megabytes,
                )
            },
            {
                SwitchRow(
                    "Big downloads on Wi-Fi only",
                    "Set-up, engine and agent updates, the agents' browser and large clones wait for Wi-Fi. Off: they ask first and show the size.",
                    settings.wifiOnlyBigDownloads,
                ) { on -> update { it.copy(wifiOnlyBigDownloads = on) } }
            },
            {
                SwitchRow(
                    "Chat videos on mobile data",
                    "Off: videos wait for Wi-Fi. Text and images always sync.",
                    settings.videosOnMobileData,
                ) { on -> update { it.copy(videosOnMobileData = on) } }
            },
        ),
    )
    Gap(12.dp)
    DataUsageCard(usage, settings.mobileDailyLimitMb)
}

@Composable
private fun DataUsageCard(usage: DataUsage, limitMb: Int) {
    OutlinedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Mobile data used", style = MaterialTheme.typography.titleSmall)
            val limitBytes = limitMb * 1_000_000L
            InfoRow(
                "Today",
                if (limitMb > 0) "${Formats.bytes(usage.todayMeteredBytes)} of ${Formats.megabytes(limitMb)}" else Formats.bytes(usage.todayMeteredBytes),
            )
            if (limitBytes > 0) {
                LinearProgressIndicator(
                    progress = { (usage.todayMeteredBytes.toFloat() / limitBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            InfoRow("This month", Formats.bytes(usage.monthMeteredBytes))
            usage.byType.entries
                .filter { it.value > 0 }
                .sortedByDescending { it.value }
                .forEach { (type, bytes) -> InfoRow("  ${Formats.dataKind(type)}", Formats.bytes(bytes)) }
            Text(
                "Only mobile data counts; Wi-Fi is free. The agents' own traffic is counted but never blocked, so they keep working.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StorageSection(graph: AppGraph, settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    val snapshot by graph.phone.snapshot.collectAsStateWithLifecycle()
    SectionLabel("Storage limits")
    SettingsGroup(
        listOf(
            {
                ChoiceRow(
                    "On this phone",
                    SettingChoices.phoneLimitGb(snapshot.storageTotalBytes, settings.phoneLimitGb),
                    settings.phoneLimitGb,
                    onPick = { gb -> update { it.copy(phoneLimitGb = gb) } },
                    why = "Higher keeps more chats and caches on the phone; lower cleans up sooner.",
                    fallbackLabel = { "$it GB" },
                )
            },
            {
                ChoiceRow(
                    "In Google Drive",
                    SettingChoices.driveLimitGb,
                    settings.driveLimitGb,
                    onPick = { gb -> update { it.copy(driveLimitGb = gb) } },
                    why = "Your Google storage is shared with Gmail and Photos; a higher share leaves them less.",
                    fallbackLabel = { "$it GB" },
                )
            },
        ),
    )
    val using = if (snapshot.at > 0) "PocketIDE uses ${Formats.bytes(snapshot.appDataBytes)} on this phone now. " else ""
    Text(
        "${using}At least 2 GB always stay free for the phone. You get a notice at 80 %, and caches are cleaned at 90 %.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
    )
}

@Composable
private fun AgentsSection(settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    SectionLabel("Agents")
    SettingsGroup(
        listOf(
            {
                ChoiceRow(
                    "Agents at the same time",
                    SettingChoices.maxAgents,
                    settings.maxAgents,
                    onPick = { n -> update { it.copy(maxAgents = n) } },
                    why = "Each open agent needs memory. Auto follows this phone's memory and heat as they change.",
                    fallbackLabel = { "$it at a time" },
                )
            },
            {
                ChoiceRow(
                    "Idle agents sleep after",
                    SettingChoices.idleSleepMinutes,
                    settings.idleSleepMinutes,
                    onPick = { m -> update { it.copy(idleSleepMinutes = m) } },
                    why = "An agent with no work for this long closes its room to save memory and battery. Its session and chat are kept.",
                    fallbackLabel = { "$it minutes" },
                )
            },
            {
                SwitchRow(
                    "Only official agents",
                    "Show only Claude Code, Codex and Antigravity. Other verified publishers stay hidden.",
                    settings.onlyOfficialAgents,
                ) { on -> update { it.copy(onlyOfficialAgents = on) } }
            },
        ),
    )
}

@Composable
private fun SecuritySection(graph: AppGraph, settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    val activity = LocalActivity.current as? FragmentActivity
    var notice by remember { mutableStateOf<String?>(null) }
    SectionLabel("Look and lock")
    SettingsGroup(
        listOf(
            {
                SwitchRow(
                    "App lock",
                    "Fingerprint or screen lock each time you come back. The app is always hidden in recent apps.",
                    settings.appLock,
                ) { on ->
                    notice = null
                    when {
                        on && !graph.appLock.deviceSecure() -> notice = "Set a screen lock on the phone first."
                        on -> update { it.copy(appLock = true) }
                        activity == null -> notice = "App lock can't be turned off from here."
                        // Turning the lock off is itself protected by the lock.
                        else -> graph.appLock.authenticate(activity, "Turn off app lock") { ok ->
                            if (ok) update { it.copy(appLock = false) }
                        }
                    }
                }
            },
            {
                ChoiceRow(
                    "Theme",
                    SettingChoices.theme,
                    settings.theme,
                    onPick = { mode -> update { it.copy(theme = mode) } },
                    why = "Only how PocketIDE looks. Same as phone follows Android's dark theme.",
                )
            },
        ),
    )
    notice?.let {
        Gap(8.dp)
        NoticeCard(it, Tone.WARN)
    }
}

@Composable
private fun ManageDataSection(nav: PocketNav, settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    SectionLabel("Manage your data")
    SettingsGroup(
        listOf(
            { ActionRow("Your data", "Everything stored, by type and size", onClick = nav::yourData, icon = Icons.Outlined.FolderOpen) },
            {
                ActionRow(
                    "Recently deleted",
                    "Kept 30 days, then erased from Drive for good",
                    onClick = nav::recentlyDeleted,
                    icon = Icons.Outlined.Delete,
                )
            },
        ),
    )
    Gap(12.dp)
    SettingsGroup(
        listOf(
            {
                ChoiceRow(
                    "Keep chats in Drive",
                    SettingChoices.keepChatsMonths,
                    settings.keepChatsMonths,
                    onPick = { m -> update { it.copy(keepChatsMonths = m) } },
                    why = "Chats with no new message for this long are removed from Drive. Shorter saves space.",
                    fallbackLabel = { "$it months after the last message" },
                )
            },
            {
                ChoiceRow(
                    "Phone copies of chats",
                    SettingChoices.phoneChatDays,
                    settings.phoneChatDays,
                    onPick = { d -> update { it.copy(phoneChatDays = d) } },
                    why = "Drive keeps every chat; an older one downloads again when you open it.",
                    fallbackLabel = { "$it days" },
                )
            },
            {
                ChoiceRow(
                    "Media copies on the phone",
                    SettingChoices.phoneMediaDays,
                    settings.phoneMediaDays,
                    onPick = { d -> update { it.copy(phoneMediaDays = d) } },
                    why = "Keeping all uses phone space but works offline; Drive keeps the originals either way.",
                    fallbackLabel = { "$it days" },
                )
            },
            {
                ChoiceRow(
                    "Project caches",
                    SettingChoices.cacheDays,
                    settings.cacheDays,
                    onPick = { d -> update { it.copy(cacheDays = d) } },
                    why = "Caches rebuild when needed. Sooner frees space; the next build then takes longer.",
                    fallbackLabel = { "After $it days unused" },
                )
            },
            {
                ChoiceRow(
                    "Unused computer",
                    SettingChoices.computerUnusedDays,
                    settings.computerUnusedDays,
                    onPick = { d -> update { it.copy(computerUnusedDays = d) } },
                    why = "Removed only when everything is synced, after a 7-day notice. Set it up again from Home when you're on Wi-Fi.",
                    fallbackLabel = { "After $it days" },
                )
            },
            {
                SwitchRow(
                    "When PocketIDE's Drive space is full",
                    "Move chats older than 12 months to Recently deleted, after a 7-day notice.",
                    settings.autoTrimOldChats,
                ) { on -> update { it.copy(autoTrimOldChats = on) } }
            },
        ),
    )
    Text(
        "Recently deleted is always 30 days. Unmerged branches and unpushed code are never removed automatically, " +
            "and Drive chats are never removed for inactivity.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
    )
    Gap(12.dp)
    SettingsGroup(
        Links.manageYourData.map { link ->
            @Composable { ActionRow(link.title, link.what, onClick = { nav.openExternal(link.url) }, external = true) }
        },
    )
}

@Composable
private fun AccountsSection(graph: AppGraph, nav: PocketNav) {
    val account by graph.gitHubAuth.account.collectAsStateWithLifecycle()
    val email by graph.driveAuth.email.collectAsStateWithLifecycle()
    SectionLabel("Accounts")
    OutlinedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow("GitHub", account?.let { "@${it.login}" } ?: "Not connected")
            InfoRow("Google Drive", email ?: "Not connected")
        }
    }
    if (account != null) {
        Gap(12.dp)
        SettingsGroup(
            listOf(
                {
                    ActionRow(
                        "Repositories PocketIDE may use",
                        "Changed on GitHub's site, in the app's installation",
                        onClick = { nav.openExternal(graph.gitHubAuth.installUrl()) },
                        external = true,
                    )
                },
            ),
        )
    }
}

@Composable
private fun AboutSection(graph: AppGraph, nav: PocketNav) {
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val update by graph.updater.state.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = Redact.text(e.message ?: "That didn't work. Try again.").take(200)
            } finally {
                busy = false
            }
        }
    }

    SectionLabel("About")
    SettingsGroup(
        listOf(
            {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoRow("Version", BuildConfig.VERSION_NAME)
                    when (val state = update) {
                        is UpdateState.Downloading -> {
                            Text("Downloading the update…", style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(progress = { state.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        }
                        else -> Unit
                    }
                }
            },
            {
                when (val state = update) {
                    UpdateState.UpToDate -> ActionRow(
                        if (busy) "Checking…" else "Check for updates",
                        if (checked && !busy) "You have the newest version." else "From PocketIDE's releases on GitHub",
                        onClick = { if (!busy) run { graph.updater.check(); checked = true } },
                    )
                    is UpdateState.Available -> ActionRow(
                        "Download version ${state.release.version}",
                        "${Formats.bytes(state.release.apkBytes)} · waits for Wi-Fi unless you allow mobile data",
                        onClick = { if (!busy) run { graph.updater.download() } },
                    )
                    is UpdateState.Downloading -> ActionRow("Downloading…", "${(state.fraction * 100).toInt()} %", onClick = {})
                    is UpdateState.Ready -> ActionRow(
                        "Install version ${state.release.version}",
                        "Its signature matches this app. Android's installer opens.",
                        onClick = { activity?.let { graph.updater.install(it) } },
                    )
                    is UpdateState.Failed -> ActionRow(
                        "Check for updates again",
                        Redact.text(state.why),
                        onClick = { if (!busy) run { graph.updater.check(); checked = true } },
                    )
                }
            },
            { ActionRow("Help", "How it works, your data, questions", onClick = { nav.help(null) }, icon = Icons.AutoMirrored.Outlined.HelpOutline) },
            { ActionRow("Terms", null, onClick = { nav.help("terms") }, icon = Icons.Outlined.Description) },
            { ActionRow("Privacy", "Who sees what", onClick = { nav.help("privacy") }, icon = Icons.Outlined.PrivacyTip) },
            { ActionRow("Privacy policy", null, onClick = { nav.help("privacy-policy") }, icon = Icons.Outlined.Description) },
        ),
    )
    error?.let {
        Gap(8.dp)
        NoticeCard(it, Tone.ERROR)
    }
}
