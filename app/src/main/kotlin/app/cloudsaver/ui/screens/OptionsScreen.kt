package app.cloudsaver.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LinearProgressIndicator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.R
import app.cloudsaver.core.logic.BackupScope
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.OutputMode
import app.cloudsaver.core.logic.Preset
import app.cloudsaver.core.logic.ScanSources
import app.cloudsaver.core.logic.SpeedMode
import app.cloudsaver.core.logic.ThemeMode
import app.cloudsaver.core.logic.VideoCodec
import app.cloudsaver.data.CloudApps
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.Routes
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.MeterBar
import app.cloudsaver.ui.components.PasswordDialog
import app.cloudsaver.ui.components.SectionHeader
import app.cloudsaver.ui.components.WarningNote
import app.cloudsaver.ui.components.SegmentedChoice
import app.cloudsaver.util.Formats

@Composable
fun OptionsScreen(vm: AppViewModel, nav: NavHostController) {
    val o by vm.options.collectAsStateWithLifecycle()
    val transferMessage by vm.transferMessage.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    val activityUnread by vm.activityUnread.collectAsStateWithLifecycle()
    val recommended by vm.recommended.collectAsStateWithLifecycle()
    val storage by vm.storageStats.collectAsStateWithLifecycle()
    val deviceFree = volumes.firstOrNull {
        (o.storageVolume.isEmpty() && it.isPrimary) || it.mediaVolumeName == o.storageVolume
    }?.freeBytes ?: 0L

    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.refreshVolumes()
        vm.refreshRecommended()
        vm.refreshStorage()
    }

    var showFolders by remember { mutableStateOf(false) }
    // Changing the layout means the cloud app has to be pointed at a different
    // folder or the backup quietly stops covering new files. Confirmed, not
    // applied on a stray tap.
    var pendingLayout by remember { mutableStateOf<OutputMode?>(null) }
    // Moving to or from the SD card applies to new files only, and the ones
    // already written stay where they are. That is worth saying before the
    // change, not discovering afterwards.
    var pendingVolume by remember { mutableStateOf<String?>(null) }
    var cloudPickerFor by remember { mutableStateOf<String?>(null) } // "single"|"photos"|"videos"

    val exportOkLabel = stringResource(R.string.transfer_export_ok)
    val importOkLabel = stringResource(R.string.transfer_import_ok)
    val failedLabel = stringResource(R.string.transfer_failed)
    val wrongPasswordLabel = stringResource(R.string.transfer_wrong_password)

    // The password is chosen before the file picker opens and used once the
    // user has picked a destination.
    var exportPassword by remember { mutableStateOf<String?>(null) }
    var askExportPassword by remember { mutableStateOf(false) }
    val pendingImport by vm.pendingImportUri.collectAsStateWithLifecycle()
    val importWrongPassword by vm.importPasswordWrong.collectAsStateWithLifecycle()
    val transferBusy by vm.transferBusy.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            vm.exportState(uri, exportPassword, exportOkLabel, failedLabel)
        }
        exportPassword = null
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importState(uri, null, importOkLabel, failedLabel, wrongPasswordLabel)
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

        SectionHeader(stringResource(R.string.opt_group_backup))
        // 1. What to back up
        OptionCard(
            stringResource(R.string.opt_scope),
            stringResource(R.string.opt_scope_hint),
            icon = IconScope,
            value = scopeLabel(o.scope)
        ) {
            SegmentedChoice(
                listOf(
                    BackupScope.ALL.name to stringResource(R.string.scope_all),
                    BackupScope.PHOTOS.name to stringResource(R.string.scope_photos),
                    BackupScope.VIDEOS.name to stringResource(R.string.scope_videos)
                ),
                o.scope.name
            ) { vm.setScope(BackupScope.valueOf(it)) }
        }

        // 2. Folders
        OptionCard(
            stringResource(R.string.opt_folders),
            stringResource(R.string.opt_folders_hint),
            icon = IconAlbums
        ) {
            OutlinedButton(onClick = { vm.loadBuckets(); showFolders = true }) {
                Text(
                    if (o.excludedBuckets.isEmpty()) stringResource(R.string.folders_all)
                    else pluralStringResource(
                        R.plurals.folders_excluded,
                        o.excludedBuckets.size,
                        o.excludedBuckets.size
                    )
                )
            }
        }

        // 3. Output folders
        OptionCard(
            stringResource(R.string.opt_output),
            stringResource(R.string.opt_output_hint),
            icon = IconLayout
        ) {
            SegmentedChoice(
                listOf(
                    OutputMode.SINGLE.name to stringResource(R.string.output_single),
                    OutputMode.SEPARATE.name to stringResource(R.string.output_separate)
                ),
                o.outputMode.name
            ) { pendingLayout = OutputMode.valueOf(it) }
            // The user has to pick this exact string inside another app, so it
            // is printed rather than described.
            FolderPaths(o.outputMode)
            CopyPathButton(o.outputMode)
        }

        OptionCard(
            stringResource(R.string.opt_cloud),
            stringResource(R.string.cloud_intended),
            icon = IconCloud,
            value = CloudApps.byId(o.cloudSingle).label
        ) {
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


        SectionHeader(stringResource(R.string.opt_group_schedule))
        // 5. Speed
        OptionCard(
            stringResource(R.string.opt_speed),
            stringResource(R.string.opt_speed_hint),
            icon = IconSpeed,
            value = speedLabel(o.speed)
        ) {
            SegmentedChoice(
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
        OptionCard(
            stringResource(R.string.opt_daily_cap),
            stringResource(R.string.opt_daily_cap_hint),
            icon = IconLimit,
            value = capLabel(o.dailyCapMb)
        ) {
            SegmentedChoice(
                Defaults.DAILY_CAP_CHOICES_MB.map { mb ->
                    mb.toString() to if (mb < 0) stringResource(R.string.unlimited) else Formats.mbLabel(mb)
                },
                o.dailyCapMb.toString()
            ) { vm.setDailyCap(it.toInt()) }
            if (o.dailyCapMb < 0) WarningText(stringResource(R.string.unlimited_warning))
            if (recommended.capLooksWrong) {
                RecommendationNote(
                    text = stringResource(
                        R.string.recommend_cap,
                        Formats.mbLabel(recommended.dailyCapMb)
                    ),
                    onApply = { vm.applyRecommended() }
                )
            }
        }


        SectionHeader(stringResource(R.string.opt_group_space))
        // 7. Two limits that sound alike and mean opposite things, so each
        // gets its own card, its own sentence, and its own live number.
        OptionCard(
            stringResource(R.string.space_min_free_title),
            stringResource(R.string.space_min_free_body),
            icon = IconFree,
            value = Formats.mbLabel(o.minFreeMb)
        ) {
            SegmentedChoice(
                Defaults.MIN_FREE_CHOICES_MB.map { it.toString() to Formats.mbLabel(it) },
                o.minFreeMb.toString()
            ) { vm.setMinFree(it.toInt()) }
            LiveValue(stringResource(R.string.space_now_free, Formats.bytes(deviceFree)))
            if (recommended.freeLooksWrong) {
                RecommendationNote(
                    text = stringResource(
                        R.string.recommend_space, Formats.mbLabel(recommended.minFreeMb)
                    ),
                    onApply = { vm.applyRecommended() }
                )
            }
        }
        OptionCard(
            stringResource(R.string.space_max_extra_title),
            stringResource(R.string.space_max_extra_body),
            icon = IconOwnSpace,
            value = capLabel(o.maxExtraMb)
        ) {
            SegmentedChoice(
                Defaults.MAX_EXTRA_CHOICES_MB.map { mb ->
                    mb.toString() to if (mb < 0) stringResource(R.string.unlimited) else Formats.mbLabel(mb)
                },
                o.maxExtraMb.toString()
            ) { vm.setMaxExtra(it.toInt()) }
            LiveValue(
                stringResource(
                    R.string.space_now_using,
                    Formats.bytes(storage.stageBytes + storage.outputBytes)
                )
            )
            if (o.maxExtraMb < 0) WarningText(stringResource(R.string.unlimited_warning))
        }
        Text(
            stringResource(R.string.space_two_things),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )

        // 7b. Storage location (13.D; shown only when an SD card exists)
        if (volumes.size > 1 || o.storageVolume.isNotEmpty()) {
            OptionCard(
                    stringResource(R.string.opt_volume),
                    stringResource(R.string.opt_volume_hint),
                    icon = IconVolume
                ) {
                SegmentedChoice(
                    volumes.map { vol ->
                        val value = if (vol.isPrimary) "" else vol.mediaVolumeName
                        value to if (vol.isPrimary) {
                            stringResource(R.string.volume_internal)
                        } else {
                            stringResource(R.string.volume_sd)
                        }
                    },
                    o.storageVolume
                ) { pendingVolume = it }
                // Only the chosen volume's capacity belongs here; the Storage
                // tab is where every volume is listed.
                val chosen = volumes.firstOrNull { vol ->
                    if (o.storageVolume.isEmpty()) vol.isPrimary
                    else vol.mediaVolumeName == o.storageVolume
                }
                if (chosen != null) {
                    val used = (chosen.totalBytes - chosen.freeBytes).coerceAtLeast(0)
                    val fraction = if (chosen.totalBytes > 0) {
                        used.toFloat() / chosen.totalBytes
                    } else {
                        0f
                    }
                    MeterBar(
                        fraction = fraction,
                        warn = fraction > 0.9f,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        stringResource(
                            R.string.volume_free_line, Formats.bytes(chosen.freeBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else if (o.storageVolume.isNotEmpty()) {
                    WarningText(stringResource(R.string.volume_missing_warning))
                }
                if (o.storageVolume.isNotEmpty()) {
                    WarningNote(stringResource(R.string.volume_sd_note))
                }
            }
        }


        SectionHeader(stringResource(R.string.opt_group_quality))
        // 8. Preset
        OptionCard(
            stringResource(R.string.opt_preset),
            stringResource(R.string.opt_preset_hint),
            icon = IconQuality,
            value = presetLabel(o.preset),
            onInfo = { nav.navigate(Routes.HELP_QUALITY) }
        ) {
            SegmentedChoice(
                listOf(
                    Preset.STORAGE_SAVER.name to stringResource(R.string.preset_storage),
                    Preset.BALANCED.name to stringResource(R.string.preset_balanced),
                    Preset.MAX_SAVER.name to stringResource(R.string.preset_max)
                ),
                o.preset.name
            ) { vm.setPreset(Preset.valueOf(it)) }
            // Say what each preset actually does, in numbers.
            ChoiceNote(
                when (o.preset) {
                    Preset.STORAGE_SAVER -> stringResource(R.string.preset_storage_detail)
                    Preset.BALANCED -> stringResource(R.string.preset_balanced_detail)
                    Preset.MAX_SAVER -> stringResource(R.string.preset_max_detail)
                }
            )
            ChoiceNote(stringResource(R.string.applies_to_new_only))
        }

        // 9. Codec
        OptionCard(
            stringResource(R.string.opt_codec),
            stringResource(R.string.opt_codec_hint),
            icon = IconCodec,
            value = o.codec.name
        ) {
            SegmentedChoice(
                listOf(
                    VideoCodec.H264.name to stringResource(R.string.codec_h264),
                    VideoCodec.HEVC.name to stringResource(R.string.codec_hevc)
                ),
                o.codec.name
            ) { vm.setCodec(VideoCodec.valueOf(it)) }
            ChoiceNote(
                when (o.codec) {
                    VideoCodec.H264 -> stringResource(R.string.codec_h264_detail)
                    VideoCodec.HEVC -> stringResource(R.string.codec_hevc_detail)
                }
            )
            ChoiceNote(stringResource(R.string.applies_to_new_only))
        }


        SectionHeader(stringResource(R.string.opt_group_appearance))
        // 10. Theme
        OptionCard(
            stringResource(R.string.opt_theme),
            stringResource(R.string.opt_theme_hint),
            icon = IconTheme,
            value = themeLabel(o.theme)
        ) {
            SegmentedChoice(
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


        SectionHeader(stringResource(R.string.opt_group_privacy))
        // Switches sit on the row itself. Wrapping one in a card that repeats
        // its own title read as two settings with the same name.
        SwitchCard(
            title = stringResource(R.string.opt_lock),
            hint = stringResource(R.string.opt_lock_hint),
            icon = IconLock,
            checked = o.appLock
        ) { vm.setAppLock(it) }
        SwitchCard(
            title = stringResource(R.string.opt_warnings),
            hint = stringResource(R.string.opt_warnings_hint),
            icon = IconAlerts,
            checked = o.warningsNotif
        ) { vm.setWarningsNotif(it) }
        SwitchCard(
            title = stringResource(R.string.opt_pause),
            hint = stringResource(R.string.opt_pause_hint),
            icon = IconPause,
            checked = o.pauseAll
        ) { vm.setPauseAll(it) }

        // The list of files the user said never to touch belongs with the
        // other safety settings, not among the colours.
        val excludedFiles by vm.neverOptimiseCount.collectAsStateWithLifecycle()
        if (excludedFiles > 0) {
            OptionCard(
                stringResource(R.string.never_optimise_title),
                stringResource(R.string.never_optimise_hint),
                icon = IconExcluded,
                value = Formats.count(excludedFiles)
            ) {
                Text(
                    pluralStringResource(
                        R.plurals.never_optimise_count, excludedFiles, excludedFiles
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
                TextButton(onClick = { vm.clearNeverOptimise() }) {
                    Text(stringResource(R.string.never_optimise_clear))
                }
            }
        }


        SectionHeader(stringResource(R.string.opt_group_backup_restore))
        // 17. Export / Import
        OptionCard(
            stringResource(R.string.opt_transfer),
            stringResource(R.string.opt_transfer_hint),
            icon = IconTransfer
        ) {
            Row {
                OutlinedButton(
                    enabled = !transferBusy,
                    onClick = { askExportPassword = true }
                ) {
                    Text(stringResource(R.string.transfer_export))
                }
                Spacer(Modifier.padding(horizontal = 6.dp))
                OutlinedButton(
                    enabled = !transferBusy,
                    onClick = { importLauncher.launch(arrayOf("*/*")) }
                ) {
                    Text(stringResource(R.string.transfer_import))
                }
            }
            if (transferBusy) {
                Text(
                    stringResource(R.string.transfer_working),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
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


        SectionHeader(stringResource(R.string.opt_group_help))
        // 18. Help and Activity. Both used to hang off Home, where they
        // competed with the one number that screen exists to show. They are
        // reference material, so they live with the other reference material.
        NavRow(
            title = stringResource(R.string.nav_help),
            hint = stringResource(R.string.help_entry),
            icon = IconHelp,
            onClick = { nav.navigate(Routes.HELP) }
        )
        NavRow(
            title = stringResource(R.string.nav_activity),
            hint = stringResource(R.string.activity_entry),
            icon = IconActivity,
            dot = activityUnread > 0,
            onClick = { nav.navigate(Routes.ACTIVITY) }
        )

        Text(
            stringResource(R.string.options_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        Spacer(Modifier.height(16.dp))
    }

    if (askExportPassword) {
        PasswordDialog(
            title = stringResource(R.string.backup_password_title),
            body = stringResource(R.string.backup_password_body),
            confirmMode = true,
            allowSkip = true,
            onDismiss = { askExportPassword = false },
            onConfirm = { password ->
                askExportPassword = false
                exportPassword = password.ifEmpty { null }
                exportLauncher.launch(
                    if (password.isEmpty()) "cloudsaver-backup.json" else "cloudsaver-backup.csb"
                )
            }
        )
    }

    pendingImport?.let { uri ->
        PasswordDialog(
            title = stringResource(R.string.restore_password_title),
            body = stringResource(R.string.restore_password_body),
            confirmMode = false,
            errorText = if (importWrongPassword) {
                stringResource(R.string.transfer_wrong_password)
            } else {
                null
            },
            onDismiss = { vm.cancelPendingImport() },
            onConfirm = { password ->
                vm.importState(uri, password, importOkLabel, failedLabel, wrongPasswordLabel)
            }
        )
    }

    pendingVolume?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingVolume = null },
            confirmButton = {
                TextButton(onClick = {
                    vm.setStorageVolume(target)
                    pendingVolume = null
                }) { Text(stringResource(R.string.volume_switch_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingVolume = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.volume_switch_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.volume_switch_body))
                    if (target.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.volume_sd_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }

    pendingLayout?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingLayout = null },
            confirmButton = {
                TextButton(onClick = {
                    vm.setOutputMode(target)
                    pendingLayout = null
                }) { Text(stringResource(R.string.output_switch_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingLayout = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.output_switch_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.output_switch_body))
                    Spacer(Modifier.height(10.dp))
                    FolderPaths(target)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.output_switch_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    if (showFolders) {
        val buckets by vm.buckets.collectAsStateWithLifecycle()
        val lockedBuckets by vm.lockedBuckets.collectAsStateWithLifecycle()
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
                    // Folders holding another pipeline's compressed copies.
                    // Shown so the absence is explained, but never selectable:
                    // re-compressing copies of copies helps nobody.
                    if (lockedBuckets.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.folders_auto_excluded),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        for ((bucket, reason) in lockedBuckets) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                            ) {
                                Checkbox(checked = false, enabled = false, onCheckedChange = null)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        bucket,
                                        maxLines = 1,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Text(
                                        stringResource(
                                            when (reason) {
                                                ScanSources.Reason.OUR_OUTPUT ->
                                                    R.string.folders_reason_ours
                                                ScanSources.Reason.HIDDEN ->
                                                    R.string.folders_reason_hidden
                                                else -> R.string.folders_reason_compressed
                                            }
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
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
    app: app.cloudsaver.data.CloudApp,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                if (app.recommended) {
                    RecommendedTag(Modifier.padding(start = 8.dp))
                }
            }
            if (installed) {
                Text(
                    stringResource(R.string.cloud_installed_mark),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (app.recommended) {
                // A tag with no reason behind it is just an advertisement.
                Text(
                    stringResource(R.string.cloud_recommended),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // What this particular app can and cannot be relied on for. The
            // same three lines for every entry, including "Other app", so no
            // choice looks safer than it is.
            Text(
                cloudPromiseLine(app.id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            app.checklistRes?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** A device-aware suggestion the user can take with one tap, or ignore. */
/** A live figure under a control, so the setting is not an abstraction. */
@Composable
private fun LiveValue(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/**
 * "Recommended" as a plain tag.
 *
 * As a filled pill it read as a third option next to the real ones, and
 * people tapped it expecting something to happen.
 */
@Composable
fun RecommendedTag(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.tag_recommended),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}

@Composable
private fun RecommendationNote(text: String, onApply: () -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onApply, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(stringResource(R.string.recommend_apply))
        }
    }
}


// Material Symbols, one set app-wide. A settings screen without icons is a
// form; with them it can be scanned.
// The collapsed row has to say what the setting is currently set to, so the
// value shown here is the same word the expanded control is showing.
@Composable
private fun scopeLabel(scope: BackupScope): String = stringResource(
    when (scope) {
        BackupScope.ALL -> R.string.scope_all
        BackupScope.PHOTOS -> R.string.scope_photos
        BackupScope.VIDEOS -> R.string.scope_videos
    }
)

@Composable
private fun speedLabel(speed: SpeedMode): String = stringResource(
    when (speed) {
        SpeedMode.SMART -> R.string.speed_smart
        SpeedMode.CHARGING_ONLY -> R.string.speed_charging
        SpeedMode.FAST -> R.string.speed_fast
    }
)

@Composable
private fun presetLabel(preset: Preset): String = stringResource(
    when (preset) {
        Preset.STORAGE_SAVER -> R.string.preset_storage
        Preset.BALANCED -> R.string.preset_balanced
        Preset.MAX_SAVER -> R.string.preset_max
    }
)

@Composable
private fun themeLabel(theme: ThemeMode): String = stringResource(
    when (theme) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
)

// Negative means no ceiling, and "Unlimited" is the word the chips use.
@Composable
private fun capLabel(mb: Int): String =
    if (mb < 0) stringResource(R.string.unlimited) else Formats.mbLabel(mb)

private val IconScope = Icons.Outlined.PhotoLibrary
private val IconAlbums = Icons.Outlined.Folder
private val IconLayout = Icons.Outlined.CreateNewFolder
private val IconCloud = Icons.Outlined.CloudUpload
private val IconSpeed = Icons.Outlined.Bolt
private val IconLimit = Icons.Outlined.DataUsage
private val IconFree = Icons.Outlined.PhoneAndroid
private val IconOwnSpace = Icons.Outlined.Storage
private val IconVolume = Icons.Outlined.SdCard
private val IconQuality = Icons.Outlined.Tune
private val IconCodec = Icons.Outlined.Movie
private val IconTheme = Icons.Outlined.Palette
private val IconLock = Icons.Outlined.Lock
private val IconAlerts = Icons.Outlined.Notifications
private val IconPause = Icons.Outlined.PauseCircle
private val IconExcluded = Icons.Outlined.Block
private val IconTransfer = Icons.Outlined.Backup
private val IconHelp = Icons.AutoMirrored.Outlined.HelpOutline
private val IconActivity = Icons.Outlined.History

private val InfoIcon = Icons.Outlined.Info

/**
 * One setting: icon, title, what it does, and the value it is set to.
 *
 * The icon is not decoration - a column of text rows reads as a form, and a
 * settings screen people are meant to understand at a glance needs something
 * to scan by. [value] repeats the current choice in words next to the title,
 * so the answer is readable without parsing the control below it.
 */
@Composable
private fun OptionCard(
    title: String,
    hint: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    value: String? = null,
    onInfo: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    AppCard(modifier = Modifier.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                androidx.compose.material3.Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .padding(end = 0.dp)
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    value?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (onInfo != null) {
                androidx.compose.material3.IconButton(onClick = onInfo) {
                    androidx.compose.material3.Icon(
                        InfoIcon,
                        contentDescription = stringResource(R.string.quality_explained_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        content()
    }
}

/**
 * A setting that is just on or off: same row shape as [OptionCard], with the
 * switch where the value would be. Tapping anywhere on the row toggles it.
 */
@Composable
private fun SwitchCard(
    title: String,
    hint: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    AppCard(
        modifier = Modifier.padding(vertical = 5.dp),
        onClick = { onChange(!checked) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

/**
 * A settings row that opens another screen instead of holding a control.
 * Same shape as [OptionCard] so the column keeps one rhythm, with a chevron
 * where the control would be.
 */
@Composable
private fun NavRow(
    title: String,
    hint: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    dot: Boolean = false,
    onClick: () -> Unit
) {
    AppCard(modifier = Modifier.padding(vertical = 5.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (dot) {
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .size(8.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            androidx.compose.material3.Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
        // Label first in its own column with a fixed gap: a long label used to
        // run under the switch, and neither could then be read.
        Text(
            label,
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** One quiet line under a choice, saying what the selection means. */
@Composable
private fun ChoiceNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
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
