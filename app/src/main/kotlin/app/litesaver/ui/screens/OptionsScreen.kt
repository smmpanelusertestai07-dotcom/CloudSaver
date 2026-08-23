package app.litesaver.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LinearProgressIndicator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.litesaver.R
import app.litesaver.core.logic.BackupScope
import app.litesaver.core.logic.Defaults
import app.litesaver.core.logic.OutputMode
import app.litesaver.core.logic.Preset
import app.litesaver.core.logic.SpeedMode
import app.litesaver.core.logic.ThemeMode
import app.litesaver.core.logic.VideoCodec
import app.litesaver.data.CloudApps
import app.litesaver.ui.AppViewModel
import app.litesaver.ui.Routes
import app.litesaver.ui.components.GlassCard
import app.litesaver.ui.components.PillChoice
import app.litesaver.util.Formats

@Composable
fun OptionsScreen(vm: AppViewModel, nav: NavHostController) {
    val o by vm.options.collectAsStateWithLifecycle()
    val transferMessage by vm.transferMessage.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshVolumes() }

    var showFolders by remember { mutableStateOf(false) }
    var cloudPickerFor by remember { mutableStateOf<String?>(null) } // "single"|"photos"|"videos"

    val exportOkLabel = stringResource(R.string.transfer_export_ok)
    val importOkLabel = stringResource(R.string.transfer_import_ok)
    val failedLabel = stringResource(R.string.transfer_failed)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            vm.exportState(uri, exportOkLabel, failedLabel)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importState(uri, importOkLabel, failedLabel)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.nav_options),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))

        // 1. What to back up
        OptionCard(stringResource(R.string.opt_scope), stringResource(R.string.opt_scope_hint)) {
            PillChoice(
                listOf(
                    BackupScope.ALL.name to stringResource(R.string.scope_all),
                    BackupScope.PHOTOS.name to stringResource(R.string.scope_photos),
                    BackupScope.VIDEOS.name to stringResource(R.string.scope_videos)
                ),
                o.scope.name
            ) { vm.setScope(BackupScope.valueOf(it)) }
        }

        // 2. Folders
        OptionCard(stringResource(R.string.opt_folders), stringResource(R.string.opt_folders_hint)) {
            OutlinedButton(onClick = { vm.loadBuckets(); showFolders = true }) {
                Text(
                    if (o.excludedBuckets.isEmpty()) stringResource(R.string.folders_all)
                    else stringResource(R.string.folders_excluded, o.excludedBuckets.size)
                )
            }
        }

        // 3. Output folders
        OptionCard(stringResource(R.string.opt_output), stringResource(R.string.opt_output_hint)) {
            PillChoice(
                listOf(
                    OutputMode.SINGLE.name to stringResource(R.string.output_single),
                    OutputMode.SEPARATE.name to stringResource(R.string.output_separate)
                ),
                o.outputMode.name
            ) { vm.setOutputMode(OutputMode.valueOf(it)) }
        }

        // 4. Cloud app(s)
        OptionCard(stringResource(R.string.opt_cloud), stringResource(R.string.cloud_intended)) {
            if (o.outputMode == OutputMode.SINGLE) {
                CloudButton(
                    stringResource(R.string.cloud_for_all),
                    o.cloudSingle
                ) { cloudPickerFor = "single" }
            } else {
                CloudButton(
                    stringResource(R.string.cloud_for_photos),
                    o.cloudPhotos
                ) { cloudPickerFor = "photos" }
                CloudButton(
                    stringResource(R.string.cloud_for_videos),
                    o.cloudVideos
                ) { cloudPickerFor = "videos" }
            }
            CloudApps.byId(o.cloudSingle).checklistRes?.let { res ->
                Text(
                    stringResource(res),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        // 5. Speed
        OptionCard(stringResource(R.string.opt_speed), stringResource(R.string.opt_speed_hint)) {
            PillChoice(
                listOf(
                    SpeedMode.SMART.name to stringResource(R.string.speed_smart),
                    SpeedMode.CHARGING_ONLY.name to stringResource(R.string.speed_charging),
                    SpeedMode.FAST.name to stringResource(R.string.speed_fast)
                ),
                o.speed.name
            ) { vm.setSpeed(SpeedMode.valueOf(it)) }
            Text(
                when (o.speed) {
                    SpeedMode.SMART -> stringResource(R.string.speed_smart_note)
                    SpeedMode.CHARGING_ONLY -> stringResource(R.string.speed_charging_note)
                    SpeedMode.FAST -> stringResource(R.string.speed_fast_note)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // 6. Daily cap
        OptionCard(stringResource(R.string.opt_daily_cap), stringResource(R.string.opt_daily_cap_hint)) {
            PillChoice(
                Defaults.DAILY_CAP_CHOICES_MB.map { mb ->
                    mb.toString() to if (mb < 0) stringResource(R.string.unlimited) else Formats.mbLabel(mb)
                },
                o.dailyCapMb.toString()
            ) { vm.setDailyCap(it.toInt()) }
            if (o.dailyCapMb < 0) WarningText(stringResource(R.string.unlimited_warning))
        }

        // 7. Phone space
        OptionCard(stringResource(R.string.opt_space), stringResource(R.string.opt_space_hint)) {
            Text(stringResource(R.string.space_min_free), style = MaterialTheme.typography.labelLarge)
            PillChoice(
                Defaults.MIN_FREE_CHOICES_MB.map { it.toString() to Formats.mbLabel(it) },
                o.minFreeMb.toString()
            ) { vm.setMinFree(it.toInt()) }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.space_max_extra), style = MaterialTheme.typography.labelLarge)
            PillChoice(
                Defaults.MAX_EXTRA_CHOICES_MB.map { mb ->
                    mb.toString() to if (mb < 0) stringResource(R.string.unlimited) else Formats.mbLabel(mb)
                },
                o.maxExtraMb.toString()
            ) { vm.setMaxExtra(it.toInt()) }
            if (o.maxExtraMb < 0) WarningText(stringResource(R.string.unlimited_warning))
        }

        // 7b. Storage location (13.D; shown only when an SD card exists)
        if (volumes.size > 1 || o.storageVolume.isNotEmpty()) {
            OptionCard(stringResource(R.string.opt_volume), stringResource(R.string.opt_volume_hint)) {
                PillChoice(
                    volumes.map { vol ->
                        val value = if (vol.isPrimary) "" else vol.mediaVolumeName
                        value to if (vol.isPrimary) {
                            stringResource(R.string.volume_internal)
                        } else {
                            stringResource(R.string.volume_sd)
                        }
                    },
                    o.storageVolume
                ) { vm.setStorageVolume(it) }
                for (vol in volumes) {
                    val used = (vol.totalBytes - vol.freeBytes).coerceAtLeast(0)
                    val fraction = if (vol.totalBytes > 0) {
                        used.toFloat() / vol.totalBytes
                    } else {
                        0f
                    }
                    Text(
                        (if (vol.isPrimary) stringResource(R.string.volume_internal)
                        else stringResource(R.string.volume_sd)) + ": " +
                            stringResource(
                                R.string.volume_free_line,
                                Formats.bytes(vol.freeBytes),
                                Formats.bytes(vol.totalBytes)
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (o.storageVolume.isNotEmpty() &&
                    volumes.none { it.mediaVolumeName == o.storageVolume }
                ) {
                    WarningText(stringResource(R.string.volume_missing_warning))
                }
                Text(
                    stringResource(R.string.volume_sd_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Cloud calculator entry
        OptionCard(stringResource(R.string.calc_title), stringResource(R.string.calc_entry_hint)) {
            OutlinedButton(onClick = { nav.navigate(Routes.CALC) }) {
                Text(stringResource(R.string.calc_open))
            }
        }

        // 8. Preset
        OptionCard(stringResource(R.string.opt_preset), stringResource(R.string.opt_preset_hint)) {
            PillChoice(
                listOf(
                    Preset.STORAGE_SAVER.name to stringResource(R.string.preset_storage),
                    Preset.BALANCED.name to stringResource(R.string.preset_balanced),
                    Preset.MAX_SAVER.name to stringResource(R.string.preset_max)
                ),
                o.preset.name
            ) { vm.setPreset(Preset.valueOf(it)) }
        }

        // 9. Codec
        OptionCard(stringResource(R.string.opt_codec), stringResource(R.string.opt_codec_hint)) {
            PillChoice(
                listOf(
                    VideoCodec.H264.name to stringResource(R.string.codec_h264),
                    VideoCodec.HEVC.name to stringResource(R.string.codec_hevc)
                ),
                o.codec.name
            ) { vm.setCodec(VideoCodec.valueOf(it)) }
            if (o.codec == VideoCodec.HEVC) WarningText(stringResource(R.string.codec_hevc_warning))
        }

        // 10. Theme
        OptionCard(stringResource(R.string.opt_theme), stringResource(R.string.opt_theme_hint)) {
            PillChoice(
                listOf(
                    ThemeMode.SYSTEM.name to stringResource(R.string.theme_system),
                    ThemeMode.LIGHT.name to stringResource(R.string.theme_light),
                    ThemeMode.DARK.name to stringResource(R.string.theme_dark)
                ),
                o.theme.name
            ) { vm.setTheme(ThemeMode.valueOf(it)) }
            SwitchRow(
                stringResource(R.string.theme_dynamic),
                o.dynamicColor
            ) { vm.setDynamicColor(it) }
        }

        // 12-16. Switches
        OptionCard(stringResource(R.string.opt_lock), stringResource(R.string.opt_lock_hint)) {
            SwitchRow(stringResource(R.string.opt_lock), o.appLock) { vm.setAppLock(it) }
        }
        OptionCard(stringResource(R.string.opt_warnings), stringResource(R.string.opt_warnings_hint)) {
            SwitchRow(stringResource(R.string.opt_warnings), o.warningsNotif) { vm.setWarningsNotif(it) }
        }
        OptionCard(stringResource(R.string.opt_freeup), stringResource(R.string.opt_freeup_hint)) {
            SwitchRow(stringResource(R.string.opt_freeup), o.showFreeUp) { vm.setShowFreeUp(it) }
            if (o.showFreeUp) {
                SwitchRow(
                    stringResource(R.string.opt_freeup_verified),
                    o.freeUpAllowVerified30
                ) { vm.setFreeUpVerified30(it) }
                if (o.freeUpAllowVerified30) WarningText(stringResource(R.string.opt_freeup_verified_warning))
            }
        }
        OptionCard(stringResource(R.string.opt_unknown), stringResource(R.string.opt_unknown_hint)) {
            SwitchRow(stringResource(R.string.opt_unknown), o.reprocessUnknown) { vm.setReprocessUnknown(it) }
            if (o.reprocessUnknown) WarningText(stringResource(R.string.opt_unknown_warning))
        }
        OptionCard(stringResource(R.string.opt_pause), stringResource(R.string.opt_pause_hint)) {
            SwitchRow(stringResource(R.string.opt_pause), o.pauseAll) { vm.setPauseAll(it) }
        }

        // 17. Export / Import
        OptionCard(stringResource(R.string.opt_transfer), stringResource(R.string.opt_transfer_hint)) {
            Row {
                OutlinedButton(onClick = { exportLauncher.launch("litesaver-state.json") }) {
                    Text(stringResource(R.string.transfer_export))
                }
                Spacer(Modifier.padding(horizontal = 6.dp))
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                    Text(stringResource(R.string.transfer_import))
                }
            }
            transferMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        Text(
            stringResource(R.string.options_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        Spacer(Modifier.height(16.dp))
    }

    if (showFolders) {
        val buckets by vm.buckets.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { showFolders = false },
            confirmButton = {
                TextButton(onClick = { showFolders = false }) { Text(stringResource(R.string.ok)) }
            },
            title = { Text(stringResource(R.string.opt_folders)) },
            text = {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .fillMaxWidth()
                ) {
                    if (buckets.isEmpty()) {
                        Text(stringResource(R.string.folders_loading))
                    }
                    for (bucket in buckets) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = bucket !in o.excludedBuckets,
                                onCheckedChange = { include ->
                                    val next = if (include) o.excludedBuckets - bucket
                                    else o.excludedBuckets + bucket
                                    vm.setExcludedBuckets(next)
                                }
                            )
                            Text(bucket, maxLines = 1, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        )
    }

    cloudPickerFor?.let { target ->
        val current = when (target) {
            "photos" -> o.cloudPhotos
            "videos" -> o.cloudVideos
            else -> o.cloudSingle
        }
        AlertDialog(
            onDismissRequest = { cloudPickerFor = null },
            confirmButton = {
                TextButton(onClick = { cloudPickerFor = null }) { Text(stringResource(R.string.ok)) }
            },
            title = { Text(stringResource(R.string.opt_cloud)) },
            text = {
                val onPick: (String) -> Unit = { id ->
                    when (target) {
                        "photos" -> vm.setCloudPhotos(id)
                        "videos" -> vm.setCloudVideos(id)
                        else -> vm.setCloudSingle(id)
                    }
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.cloud_intended),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.cloud_section_e2ee),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    for (app in CloudApps.SELECTABLE.filter { it.e2ee }) {
                        CloudPickRow(app, current, onPick)
                    }
                    Text(
                        stringResource(R.string.cloud_section_also),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    for (app in CloudApps.SELECTABLE.filter { !it.e2ee }) {
                        CloudPickRow(app, current, onPick)
                    }
                    for (app in CloudApps.ALL.filter { !it.supported }) {
                        Column(Modifier.padding(top = 8.dp)) {
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            app.unsupportedReasonRes?.let { res ->
                                Text(
                                    stringResource(res),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun CloudPickRow(
    app: app.litesaver.data.CloudApp,
    current: String,
    onPick: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val installed = app.packages.isNotEmpty() &&
        CloudApps.installedPackage(context, app) != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        RadioButton(selected = app.id == current, onClick = { onPick(app.id) })
        Column(Modifier.weight(1f)) {
            Text(
                buildString {
                    append(app.label)
                    if (app.recommended) {
                        append(" - ")
                        append(stringResource(R.string.cloud_recommended))
                    }
                }
            )
            if (installed) {
                Text(
                    stringResource(R.string.cloud_installed_mark),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun OptionCard(title: String, hint: String, content: @Composable () -> Unit) {
    GlassCard(modifier = Modifier.padding(vertical = 5.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun WarningText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun CloudButton(label: String, cloudId: String, onClick: () -> Unit) {
    val app = CloudApps.byId(cloudId)
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(top = 4.dp)) {
        Text("$label: ${app.label}")
    }
}
