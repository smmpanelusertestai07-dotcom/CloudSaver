package app.cloudsaver.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.R
import app.cloudsaver.core.logic.RunDecider
import app.cloudsaver.data.prefs.Options
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.Routes
import app.cloudsaver.ui.components.AnimatedNumber
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.BrandMark
import app.cloudsaver.ui.components.HeroCard
import app.cloudsaver.ui.components.MeterBar
import app.cloudsaver.ui.components.MetricTile
import app.cloudsaver.ui.components.SectionHeader
import app.cloudsaver.ui.components.StatusChip
import app.cloudsaver.util.Formats
import app.cloudsaver.util.OemPages
import app.cloudsaver.util.PowerPages

@Composable
fun HomeScreen(vm: AppViewModel, nav: NavHostController) {
    val options by vm.options.collectAsStateWithLifecycle()
    val counters by vm.counters.collectAsStateWithLifecycle()
    val savedBytes by vm.savedBytes.collectAsStateWithLifecycle()
    val processed by vm.processedCount.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val confirmResult by vm.confirmResult.collectAsStateWithLifecycle()
    val foreignUris by vm.foreignUris.collectAsStateWithLifecycle()
    val tampered by vm.tampered.collectAsStateWithLifecycle()
    val savings by vm.savings.collectAsStateWithLifecycle()
    val budget by vm.budget.collectAsStateWithLifecycle()
    val unread by vm.activityUnread.collectAsStateWithLifecycle()
    val asIs by vm.asIs.collectAsStateWithLifecycle()
    val canConfirm by vm.cloudHasFreeUp.collectAsStateWithLifecycle()
    val skipReasons by vm.skipReasons.collectAsStateWithLifecycle()
    val statusWaiting by vm.statusWaiting.collectAsStateWithLifecycle()
    val power by vm.powerRequirements.collectAsStateWithLifecycle()
    var explain by remember { mutableStateOf<Int?>(null) }
    val projection by vm.projectedSavings.collectAsStateWithLifecycle()
    val findSpace by vm.findSpace.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        vm.refreshHealth()
        vm.detectForeignFiles()
        vm.refreshBudget()
        vm.refreshAsIs()
        vm.refreshCloudCaps()
        vm.refreshSkipReasons()
        vm.refreshPowerRequirements()
        vm.refreshProjection()
        if (options.reclaimReminderGb > 0) vm.refreshFindSpace()
    }

    val cleanupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        vm.onDeleteDialogResult(result.resultCode == android.app.Activity.RESULT_OK)
    }
    val legacyIntent by vm.legacyDeleteIntent.collectAsStateWithLifecycle()
    LaunchedEffect(legacyIntent) {
        legacyIntent?.let {
            cleanupLauncher.launch(IntentSenderRequest.Builder(it).build())
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // Title row
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(size = 34.dp)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { nav.navigate(Routes.HELP) }) {
                Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.nav_help))
            }
        }

        // Shown once, after an old placeholder image was cleaned up.
        AnimatedVisibility(
            visible = options.placeholderRemoved,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AppCard(modifier = Modifier.padding(top = 10.dp)) {
                Text(
                    stringResource(R.string.placeholder_removed_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.placeholder_removed_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                TextButton(onClick = { vm.dismissPlaceholderNotice() }) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        }

        AnimatedVisibility(
            visible = tampered,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AppCard(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    stringResource(R.string.tamper_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    stringResource(R.string.tamper_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // Hero: the one number that matters, plus what the app is doing.
        Spacer(Modifier.height(12.dp))
        HeroCard {
            Text(
                stringResource(R.string.hero_saved_label),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.85f)
            )
            AnimatedNumber(
                value = Formats.bytes(savedBytes),
                color = Color.White,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                pluralStringResource(R.plurals.hero_saved_sub, processed, processed),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
            // What finishing the queue would be worth, from the same measured
            // profile every other estimate uses.
            if (projection > 0) {
                Text(
                    stringResource(R.string.hero_projection, Formats.bytes(projection)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            // A single 4K clip can outweigh a thousand photos, so the two
            // numbers are worth keeping apart: this is the line that tells
            // someone whether turning videos on was worth it.
            if (savings.totalBytes > 0) {
                Text(
                    stringResource(
                        R.string.hero_saved_split,
                        Formats.bytes(savings.photoBytes),
                        Formats.bytes(savings.videoBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            androidx.compose.animation.Crossfade(
                targetState = statusLine(options, statusWaiting),
                label = "statusLine"
            ) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
            Text(
                stringResource(R.string.last_run, Formats.dateTime(options.lastRunAt)),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f)
            )
        }

        // Things that need the user's attention.
        val anyPower = power.any { !(it.readable && it.satisfied) }
        val reclaimReady = options.reclaimReminderGb > 0 &&
            findSpace.reclaimableBytes >= options.reclaimReminderGb * 1_000_000_000L
        val anyHealth = health.paused || anyPower || health.usageAccessOff ||
            health.cloudMissing || health.spaceLow || health.backgroundWorkStopped ||
            reclaimReady
        AnimatedVisibility(
            visible = anyHealth,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                SectionHeader(stringResource(R.string.section_attention))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (health.paused) {
                        StatusChip(stringResource(R.string.chip_paused)) {
                            nav.navigate(Routes.OPTIONS)
                        }
                    }
                    // Each of these opens the exact page for this phone's
                    // skin, not a generic app-info screen the user then has to
                    // hunt through.
                    for (requirement in power) {
                        if (requirement.readable && requirement.satisfied) continue
                        StatusChip(powerChipLabel(requirement.id)) {
                            vm.openPowerPage(requirement.id)
                        }
                    }
                    if (health.usageAccessOff) {
                        StatusChip(stringResource(R.string.chip_usage)) {
                            OemPages.openUsageAccess(context)
                        }
                    }
                    if (health.cloudMissing) {
                        StatusChip(stringResource(R.string.chip_cloud)) {
                            nav.navigate(Routes.OPTIONS)
                        }
                    }
                    if (health.spaceLow) {
                        StatusChip(stringResource(R.string.chip_space)) {
                            nav.navigate(Routes.STORAGE)
                        }
                    }
                    // Opt-in only, and a chip rather than a notification: an
                    // app that pings you to delete your photos is one you
                    // learn to ignore.
                    if (options.reclaimReminderGb > 0 &&
                        findSpace.reclaimableBytes >=
                        options.reclaimReminderGb * 1_000_000_000L
                    ) {
                        StatusChip(
                            stringResource(
                                R.string.chip_reclaim,
                                Formats.bytes(findSpace.reclaimableBytes)
                            )
                        ) { nav.navigate(Routes.FREE_UP) }
                    }
                    // The phone stopped running us. Nothing else on this row
                    // would show it, and the app would just look idle.
                    if (health.backgroundWorkStopped) {
                        StatusChip(stringResource(R.string.chip_stopped)) {
                            vm.openPowerPage(PowerPages.ID_BATTERY_UNRESTRICTED)
                        }
                    }
                }
            }
        }

        SectionHeader(stringResource(R.string.section_progress))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                Formats.count(counters.waiting),
                stringResource(R.string.count_waiting),
                Modifier.weight(1f),
                onClick = { explain = R.string.explain_waiting }
            )
            MetricTile(
                Formats.count(counters.inFolder),
                stringResource(R.string.count_in_folder),
                Modifier.weight(1f),
                onClick = { explain = R.string.explain_in_folder }
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                Formats.count(counters.confirmed),
                stringResource(R.string.count_confirmed),
                Modifier.weight(1f),
                highlight = true,
                onClick = { explain = R.string.explain_backed_up }
            )
            // A "Skipped: 0" tile is a question with no answer; it appears
            // only when there is something to explain.
            if (counters.skipped > 0) {
                MetricTile(
                    Formats.count(counters.skipped),
                    stringResource(R.string.count_skipped),
                    Modifier.weight(1f),
                    onClick = {
                        vm.filesState.value = "SKIP"
                        nav.navigate(Routes.FILES)
                    }
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        if (counters.skipped > 0 && skipReasons.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for ((reason, count) in skipReasons) {
                    androidx.compose.material3.AssistChip(
                        onClick = {
                            vm.filesState.value = "SKIP"
                            nav.navigate(Routes.FILES)
                        },
                        label = {
                            Text(
                                stringResource(
                                    R.string.asis_card_line, count, skipReasonLabel(reason)
                                )
                            )
                        }
                    )
                }
            }
        }

        // Today's upload allowance, and when it refills. Without this, an app
        // that is deliberately holding files back looks like an app that has
        // quietly stopped.
        if (!budget.unlimited && budget.totalBytes > 0) {
            Spacer(Modifier.height(14.dp))
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.budget_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(
                            R.string.budget_used,
                            Formats.bytes(budget.usedBytes),
                            Formats.bytes(budget.totalBytes)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MeterBar(
                    fraction = budget.fraction,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    if (budget.spent) {
                        stringResource(
                            R.string.budget_spent, Formats.time(budget.resetsAt)
                        )
                    } else {
                        stringResource(R.string.budget_paced)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.runNow() }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.btn_run_now))
            }
            // The button only does anything on a cloud that removes its own
            // uploads: elsewhere there is nothing for it to observe, and an
            // action that always reports "0 confirmed" teaches people the app
            // is broken.
            if (canConfirm) {
                OutlinedButton(
                    onClick = { vm.startConfirmFlow() },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.btn_confirm_uploads)) }
            }
        }
        Text(
            if (canConfirm) {
                stringResource(R.string.confirm_explainer)
            } else {
                stringResource(R.string.confirm_unavailable)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )

        AnimatedVisibility(
            visible = confirmResult != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AppCard(modifier = Modifier.padding(top = 12.dp), tonal = true) {
                Text(
                    stringResource(R.string.confirm_result_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    pluralStringResource(
                        R.plurals.confirm_result_line,
                        confirmResult ?: 0,
                        confirmResult ?: 0
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { vm.dismissConfirmResult() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        AppCard(onClick = { nav.navigate(Routes.ACTIVITY) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.nav_activity),
                            style = MaterialTheme.typography.titleMedium
                        )
                        // A dot rather than a count: the number of log lines is
                        // not news, the fact that something happened is.
                        if (unread > 0) {
                            Box(
                                Modifier
                                    .padding(start = 8.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.activity_entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Files copied byte-for-byte. Left unexplained these look like
        // failures; named, they are the app refusing to damage something.
        AnimatedVisibility(
            visible = asIs.count > 0,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AppCard(
                modifier = Modifier.padding(top = 12.dp),
                onClick = { nav.navigate(Routes.FILES) }
            ) {
                Text(
                    pluralStringResource(R.plurals.asis_card_title, asIs.count, asIs.count),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.asis_card_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                for ((reason, count) in asIs.reasons.take(3)) {
                    Text(
                        stringResource(
                            R.string.asis_card_line, count, asIsReasonLabel(reason)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = foreignUris.isNotEmpty() && !tampered,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AppCard(modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    stringResource(R.string.old_files_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    pluralStringResource(
                        R.plurals.old_files_text, foreignUris.size, foreignUris.size
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row {
                    TextButton(onClick = {
                        val sender = vm.requestDelete(foreignUris) { vm.onForeignCleaned() }
                        sender?.let {
                            cleanupLauncher.launch(IntentSenderRequest.Builder(it).build())
                        }
                    }) { Text(stringResource(R.string.old_files_clean)) }
                    TextButton(onClick = { vm.onForeignCleaned() }) {
                        Text(stringResource(R.string.old_files_keep))
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }

    // Tapping a count says what that count means, rather than sending the
    // user to the FAQ to find out.
    explain?.let { res ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { explain = null },
            confirmButton = {
                TextButton(onClick = { explain = null }) { Text(stringResource(R.string.ok)) }
            },
            text = { Text(stringResource(res)) }
        )
    }
}

/** The label for a background-work chip, by requirement. */
@Composable
private fun powerChipLabel(id: String): String = when (id) {
    PowerPages.ID_BATTERY_UNRESTRICTED -> stringResource(R.string.chip_battery)
    PowerPages.ID_AUTO_LAUNCH -> stringResource(R.string.chip_auto_launch)
    PowerPages.ID_BACKGROUND_ACTIVITY -> stringResource(R.string.chip_background)
    else -> stringResource(R.string.chip_battery)
}

/** Plain-English reason a file was skipped. */
@Composable
fun skipReasonLabel(reason: String): String = when (reason) {
    "removed_before_upload" -> stringResource(R.string.skip_removed_early)
    "no_uri" -> stringResource(R.string.skip_unreadable)
    "out_of_memory" -> stringResource(R.string.skip_too_large)
    "user_excluded" -> stringResource(R.string.skip_user_excluded)
    "duplicate" -> stringResource(R.string.skip_duplicate)
    else -> stringResource(R.string.skip_other)
}

/**
 * One line that always tells the truth: paused, working, waiting for a
 * specific condition (13.G), or all done.
 */
@Composable
private fun statusLine(options: Options, waiting: Int): String {
    if (options.pauseAll) return stringResource(R.string.status_paused)
    if (waiting > 0) {
        val wait = runCatching { RunDecider.Wait.valueOf(options.waitReason) }
            .getOrDefault(RunDecider.Wait.NONE)
        val floor = RunDecider.batteryFloor(options.speed)
        val reason = when (wait) {
            RunDecider.Wait.NONE, RunDecider.Wait.PAUSED -> null
            RunDecider.Wait.BATTERY_SAVER -> stringResource(R.string.wait_saver)
            RunDecider.Wait.TOO_HOT -> stringResource(R.string.wait_hot)
            RunDecider.Wait.NOT_CHARGING -> stringResource(R.string.wait_charger)
            RunDecider.Wait.BATTERY_LOW -> stringResource(R.string.wait_battery, floor)
            RunDecider.Wait.SCREEN_ON -> stringResource(R.string.wait_screen)
            RunDecider.Wait.BUDGET_USED -> stringResource(R.string.wait_budget)
            RunDecider.Wait.PHOTO_CAP -> stringResource(R.string.wait_photo_cap)
        }
        return reason ?: pluralStringResource(R.plurals.status_working, waiting, waiting)
    }
    return if (options.lastRunAt > 0) {
        stringResource(R.string.status_idle)
    } else {
        stringResource(R.string.status_fresh)
    }
}
