package app.litesaver.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.litesaver.R
import app.litesaver.core.logic.RunDecider
import app.litesaver.data.prefs.Options
import app.litesaver.ui.AppViewModel
import app.litesaver.ui.Routes
import app.litesaver.ui.components.GlassCard
import app.litesaver.ui.components.HealthChip
import app.litesaver.ui.components.StatTile
import app.litesaver.util.Formats
import app.litesaver.util.OemPages

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
        Spacer(Modifier.height(12.dp))
        if (tampered) {
            GlassCard(modifier = Modifier.padding(bottom = 10.dp)) {
                Text(
                    stringResource(R.string.tamper_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    stringResource(R.string.tamper_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    statusLine(options, counters.waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { nav.navigate(Routes.HELP) }) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = stringResource(R.string.nav_help)
                )
            }
        }

        // Health chips
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (health.paused) {
                HealthChip(stringResource(R.string.chip_paused)) { nav.navigate(Routes.OPTIONS) }
            }
            if (health.batteryRestricted) {
                HealthChip(stringResource(R.string.chip_battery)) {
                    OemPages.requestIgnoreBatteryOptimizations(context)
                }
            }
            if (health.usageAccessOff) {
                HealthChip(stringResource(R.string.chip_usage)) {
                    OemPages.openUsageAccess(context)
                }
            }
            if (health.cloudMissing) {
                HealthChip(stringResource(R.string.chip_cloud)) { nav.navigate(Routes.OPTIONS) }
            }
            if (health.spaceLow) {
                HealthChip(stringResource(R.string.chip_space)) { nav.navigate(Routes.STORAGE) }
            }
        }

        // Counters
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(counters.waiting.toString(), stringResource(R.string.count_waiting), Modifier.weight(1f))
            StatTile(counters.inFolder.toString(), stringResource(R.string.count_in_folder), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(counters.confirmed.toString(), stringResource(R.string.count_confirmed), Modifier.weight(1f))
            StatTile(counters.likely.toString(), stringResource(R.string.count_likely), Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        GlassCard {
            Text(
                stringResource(R.string.savings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.savings_line, Formats.bytes(savedBytes), processed),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(R.string.last_run, Formats.dateTime(options.lastRunAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))
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
            modifier = Modifier.padding(top = 6.dp)
        )

        confirmResult?.let { n ->
            Spacer(Modifier.height(10.dp))
            GlassCard {
                Text(
                    stringResource(R.string.confirm_result_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.confirm_result_line, n),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { vm.dismissConfirmResult() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        GlassCard(onClick = { nav.navigate(Routes.CALC) }) {
            Text(
                stringResource(R.string.calc_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.calc_entry_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (foreignUris.isNotEmpty() && !tampered) {
            Spacer(Modifier.height(10.dp))
            GlassCard {
                Text(
                    stringResource(R.string.old_files_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
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
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { vm.onForeignCleaned() }) {
                        Text(stringResource(R.string.old_files_keep))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Home always says what the app is doing - or, when it is waiting, exactly
 * what it is waiting for (13.G).
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
