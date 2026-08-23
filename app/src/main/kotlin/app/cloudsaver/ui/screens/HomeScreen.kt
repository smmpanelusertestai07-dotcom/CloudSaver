package app.cloudsaver.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
import app.cloudsaver.ui.components.HeroCard
import app.cloudsaver.ui.components.MetricTile
import app.cloudsaver.ui.components.SectionHeader
import app.cloudsaver.ui.components.StatusChip
import app.cloudsaver.util.Formats
import app.cloudsaver.util.OemPages

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
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        vm.refreshHealth()
        vm.detectForeignFiles()
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
            Icon(
                painterResource(R.drawable.ic_stat_cloud),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            )
            IconButton(onClick = { nav.navigate(Routes.HELP) }) {
                Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.nav_help))
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
                stringResource(R.string.hero_saved_sub, processed),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                statusLine(options, counters.waiting),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Text(
                stringResource(R.string.last_run, Formats.dateTime(options.lastRunAt)),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f)
            )
        }

        // Things that need the user's attention.
        val anyHealth = health.paused || health.batteryRestricted || health.usageAccessOff ||
            health.cloudMissing || health.spaceLow
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
                    if (health.batteryRestricted) {
                        StatusChip(stringResource(R.string.chip_battery)) {
                            OemPages.requestIgnoreBatteryOptimizations(context)
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
                }
            }
        }

        SectionHeader(stringResource(R.string.section_progress))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                counters.waiting.toString(),
                stringResource(R.string.count_waiting),
                Modifier.weight(1f)
            )
            MetricTile(
                counters.inFolder.toString(),
                stringResource(R.string.count_in_folder),
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                counters.confirmed.toString(),
                stringResource(R.string.count_confirmed),
                Modifier.weight(1f),
                highlight = true
            )
            MetricTile(
                counters.likely.toString(),
                stringResource(R.string.count_likely),
                Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.runNow() }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.btn_run_now))
            }
            OutlinedButton(onClick = { vm.startConfirmFlow() }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.btn_confirm_uploads))
            }
        }
        Text(
            stringResource(R.string.confirm_explainer),
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
                    stringResource(R.string.confirm_result_line, confirmResult ?: 0),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { vm.dismissConfirmResult() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        AppCard(onClick = { nav.navigate(Routes.CALC) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.calc_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.calc_entry_hint),
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
                    stringResource(R.string.old_files_text, foreignUris.size),
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
        return reason ?: stringResource(R.string.status_working, waiting)
    }
    return if (options.lastRunAt > 0) {
        stringResource(R.string.status_idle)
    } else {
        stringResource(R.string.status_fresh)
    }
}
