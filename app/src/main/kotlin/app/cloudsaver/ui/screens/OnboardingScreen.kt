package app.cloudsaver.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudsaver.R
import app.cloudsaver.core.logic.OnboardingSteps
import app.cloudsaver.core.logic.OnboardingSteps.Step
import app.cloudsaver.core.logic.OutputPaths
import app.cloudsaver.data.CloudApps
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.BrandMark
import app.cloudsaver.ui.components.KeyValueRow
import app.cloudsaver.ui.components.PasswordDialog
import app.cloudsaver.util.Formats
import app.cloudsaver.util.OemPages
import app.cloudsaver.util.PowerPages
import app.cloudsaver.util.Permissions
import app.cloudsaver.ui.components.SegmentedChoice
import app.cloudsaver.ui.components.TrialCard

/**
 * One-time setup.
 *
 * The position of a step is stated in exactly one place - the header and the
 * dots, both read from [OnboardingSteps]. No card title carries a number, so
 * the two can no longer disagree the way "Step 6 of 7" once sat above a card
 * headed "5.".
 */
@Composable
fun OnboardingScreen(vm: AppViewModel) {
    val options by vm.options.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    // The options flow starts on defaults and the stored value arrives a frame
    // or two later, so the saved step cannot simply be read at composition -
    // doing that sent everyone back to the welcome card. Instead the position
    // follows the stored value until the first tap, and is the user's from
    // then on.
    var step by rememberSaveable { mutableIntStateOf(0) }
    var moved by rememberSaveable { mutableStateOf(false) }
    // Where setup was when it was last left, for the banner. Frozen at the
    // first tap so it does not follow the user forward.
    var resumedAt by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(options.onboardingStep, moved) {
        if (moved) return@LaunchedEffect
        val stored = options.onboardingStep.coerceIn(0, OnboardingSteps.TOTAL - 1)
        if (stored > step) {
            step = stored
            resumedAt = stored
        }
    }

    fun goTo(next: Int) {
        moved = true
        step = next.coerceIn(0, OnboardingSteps.TOTAL - 1)
        vm.setOnboardingStep(step)
    }

    fun go(next: Step) = goTo(OnboardingSteps.indexOf(next))

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { go(if (Permissions.hasMediaRead(context)) Step.ALBUMS else Step.MEDIA) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { go(Step.BATTERY) }

    val importOkLabel = stringResource(R.string.transfer_import_ok)
    val failedLabel = stringResource(R.string.transfer_failed)
    val wrongPasswordLabel = stringResource(R.string.transfer_wrong_password)
    val pendingImport by vm.pendingImportUri.collectAsStateWithLifecycle()
    val importWrongPassword by vm.importPasswordWrong.collectAsStateWithLifecycle()
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importState(uri, null, importOkLabel, failedLabel, wrongPasswordLabel)
        }
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

    val transferMessage by vm.transferMessage.collectAsStateWithLifecycle()
    val testItems by vm.testRun.collectAsStateWithLifecycle()
    val testRunning by vm.testRunning.collectAsStateWithLifecycle()
    val trialSize by vm.trialSize.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(size = 44.dp)
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        Text(
            stringResource(R.string.onb_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(12.dp))
        // Setup was abandoned partway last time. Say so, and say the thing
        // that actually matters: nothing ran while they were away.
        if (resumedAt > 0) {
            AppCard(modifier = Modifier.padding(top = 16.dp), tonal = true) {
                Text(
                    stringResource(R.string.onb_resume_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.onb_resume_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            stringResource(R.string.onb_step_counter, step + 1, OnboardingSteps.TOTAL),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 18.dp)
        )
        StepDots(current = step, total = OnboardingSteps.TOTAL)

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                // Slide the way the user is travelling through the steps.
                val forward = targetState > initialState
                val width = if (forward) 1 else -1
                (slideInHorizontally(tween(260)) { it * width / 3 } + fadeIn(tween(260)))
                    .togetherWith(
                        slideOutHorizontally(tween(200)) { -it * width / 3 } + fadeOut(tween(160))
                    )
                    .using(SizeTransform(clip = false))
            },
            label = "onboardingStep"
        ) { shown ->
        when (OnboardingSteps.at(shown)) {
            Step.WELCOME -> StepCard(
                title = stringResource(R.string.onb0_title),
                text = stringResource(R.string.onb0_text),
                buttonLabel = stringResource(R.string.onb_start),
                onButton = { go(Step.MEDIA) }
            ) {
                TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                    Text(stringResource(R.string.onb0_import))
                }
                transferMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            Step.MEDIA -> StepCard(
                title = stringResource(R.string.onb1_title),
                text = stringResource(R.string.onb1_text),
                buttonLabel = if (Permissions.hasMediaRead(context)) {
                    stringResource(R.string.onb_done_next)
                } else {
                    stringResource(R.string.onb1_grant)
                },
                onButton = {
                    if (Permissions.hasMediaRead(context)) go(Step.ALBUMS)
                    else permissionLauncher.launch(Permissions.mediaPermissionsToRequest())
                }
            ) {
                TextButton(onClick = { OemPages.openAppInfo(context) }) {
                    Text(stringResource(R.string.onb1_appinfo))
                }
            }

            Step.ALBUMS -> {
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadBuckets() }
                val buckets by vm.buckets.collectAsStateWithLifecycle()
                val locked by vm.lockedBuckets.collectAsStateWithLifecycle()
                val included = buckets.count { it !in options.excludedBuckets }
                StepCard(
                    title = stringResource(R.string.onb_albums_title),
                    text = stringResource(R.string.onb_albums_text),
                    // No Skip. Everything else here is a permission the app can
                    // work without; this is the list of someone's photos.
                    buttonLabel = stringResource(R.string.onb_albums_confirm),
                    onButton = { go(Step.NOTIFICATIONS) }
                ) {
                    if (buckets.isEmpty()) {
                        Text(
                            stringResource(R.string.folders_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            pluralStringResource(
                                R.plurals.onb_albums_selected, included, included
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            enabled = buckets.isNotEmpty(),
                            onClick = {
                                vm.setExcludedBuckets(
                                    if (included == 0) emptySet() else buckets.toSet()
                                )
                            }
                        ) {
                            Text(
                                stringResource(
                                    if (included == 0) R.string.onb_albums_all
                                    else R.string.onb_albums_none
                                )
                            )
                        }
                    }
                    for (bucket in buckets) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = bucket !in options.excludedBuckets,
                                onCheckedChange = { include ->
                                    vm.setExcludedBuckets(
                                        if (include) options.excludedBuckets - bucket
                                        else options.excludedBuckets + bucket
                                    )
                                }
                            )
                            Text(bucket, maxLines = 1, modifier = Modifier.weight(1f))
                        }
                    }
                    // Folders that already hold another app's compressed
                    // copies. Named so the absence is explained rather than
                    // looking like the scan missed them.
                    if (locked.isNotEmpty()) {
                        Text(
                            stringResource(R.string.folders_auto_excluded),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        for ((bucket, _) in locked) {
                            Text(
                                bucket,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.onb_albums_changeable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }

            Step.NOTIFICATIONS -> StepCard(
                title = stringResource(R.string.onb2_title),
                text = stringResource(R.string.onb2_text),
                buttonLabel = stringResource(R.string.onb2_grant),
                onButton = {
                    if (android.os.Build.VERSION.SDK_INT >= 33 && !Permissions.hasNotifications(context)) {
                        notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        go(Step.BATTERY)
                    }
                },
                onSkip = { go(Step.BATTERY) }
            )

            Step.BATTERY -> {
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshPowerRequirements() }
                val requirements by vm.powerRequirements.collectAsStateWithLifecycle()
                StepCard(
                    title = stringResource(R.string.onb3_title),
                    text = stringResource(R.string.onb3_text),
                    buttonLabel = stringResource(R.string.onb_done_next),
                    onButton = { go(Step.USAGE) },
                    onSkip = { go(Step.USAGE) }
                ) {
                    // One row per thing this particular phone can break, each
                    // opening the page that holds that switch.
                    for (requirement in requirements) {
                        PowerRow(requirement) {
                            vm.openPowerPage(requirement.id)
                            vm.refreshPowerRequirements()
                        }
                    }
                }
            }

            Step.USAGE -> StepCard(
                title = stringResource(R.string.onb4_title),
                text = stringResource(R.string.onb4_text),
                buttonLabel = stringResource(R.string.onb4_grant),
                onButton = { OemPages.openUsageAccess(context) },
                onSkip = { go(Step.CLOUD) }
            ) {
                Button(onClick = { go(Step.CLOUD) }, modifier = Modifier.padding(top = 6.dp)) {
                    Text(stringResource(R.string.onb_done_next))
                }
            }

            Step.CLOUD -> {
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.detectAndPersistCloud() }
                val detection by vm.cloudDetection.collectAsStateWithLifecycle()
                val link by vm.linkState.collectAsStateWithLifecycle()
                var picking by remember { mutableStateOf(false) }
                val chosen = detection.chosen
                StepCard(
                    title = stringResource(R.string.onb5_title),
                    text = stringResource(R.string.cloud_intended),
                    // The primary action is last, after everything the user
                    // has to read and do.
                    buttonLabel = stringResource(R.string.onb_done_next),
                    onButton = { go(Step.READY) }
                ) {
                    Text(
                        stringResource(R.string.onb5_found, chosen.label),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    FolderPaths(options.outputMode)
                    chosen.checklistRes?.let { res ->
                        Text(
                            stringResource(res),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    Text(
                        cloudPromiseLine(chosen.id),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    if (detection.needsChoice) {
                        Text(
                            stringResource(R.string.onb5_several),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    if (chosen.packages.isNotEmpty()) {
                        OutlinedButton(onClick = {
                            CloudApps.launch(context, chosen.id)
                            vm.clearLinkState()
                        }) { Text(stringResource(R.string.onb5_open, chosen.label)) }
                    }
                    OutlinedButton(onClick = { vm.verifyCloudLink() }) {
                        Text(stringResource(R.string.onb5_check))
                    }
                    OutlinedButton(onClick = { picking = true }) {
                        Text(stringResource(R.string.cloud_use_different))
                    }
                    // Never "set up correctly" on faith: only what was checked.
                    link?.let { state ->
                        Text(
                            linkStateLine(state),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state == AppViewModel.LinkState.CONNECTED) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                if (picking) {
                    CloudPickerDialog(
                        current = chosen.id,
                        onPick = {
                            vm.chooseCloud(it)
                            picking = false
                        },
                        onDismiss = { picking = false }
                    )
                }
            }

            Step.READY -> StepCard(
                title = stringResource(R.string.onb_ready_title),
                text = stringResource(R.string.onb_ready_text),
                // The whole app is idle until this tap. Nothing has been
                // scheduled, nothing has been copied, nothing has been read
                // beyond the trial the user asked for.
                buttonLabel = stringResource(R.string.onb_ready_start),
                onButton = { vm.finishOnboarding() }
            ) {
                val allAlbums by vm.buckets.collectAsStateWithLifecycle()
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadBuckets() }
                val includedAlbums = allAlbums.count { it !in options.excludedBuckets }
                SummaryLine(
                    stringResource(R.string.onb_ready_what),
                    scopeSummary(options.scope, includedAlbums, allAlbums.size)
                )
                SummaryLine(
                    stringResource(R.string.onb_ready_quality),
                    presetSummary(options.preset)
                )
                SummaryLine(
                    stringResource(R.string.onb_ready_when),
                    speedSummary(options.speed)
                )
                SummaryLine(
                    stringResource(R.string.onb_ready_cloud),
                    CloudApps.byId(options.cloudSingle).label
                )
                // Only where there is a card to choose. On a phone without
                // one this question has a single possible answer, and asking
                // it anyway is a step that teaches nothing.
                val volumes by vm.volumes.collectAsStateWithLifecycle()
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshVolumes() }
                if (volumes.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.onb_ready_where),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    SegmentedChoice(
                        volumes.map { vol ->
                            val value = if (vol.isPrimary) "" else vol.mediaVolumeName
                            value to stringResource(
                                if (vol.isPrimary) R.string.volume_internal
                                else R.string.volume_sd
                            )
                        },
                        options.storageVolume
                    ) { vm.setStorageVolume(it) }
                    Text(
                        stringResource(R.string.onb_ready_sd_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(Modifier.height(10.dp))
                // The folder is printed, not described: this exact string is
                // what has to be picked inside the cloud app.
                FolderPaths(options.outputMode)
                CopyPathButton(options.outputMode)

                Spacer(Modifier.height(12.dp))
                TrialCard(
                    size = trialSize,
                    running = testRunning,
                    results = testItems,
                    onRun = { vm.startTestRun() }
                )

            }
        }
        }

        if (step > 0) {
            TextButton(onClick = { goTo(step - 1) }) { Text(stringResource(R.string.back)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * One background-work switch this phone needs.
 *
 * Where Android reports the state it is shown; where no API can read it -
 * which is every OEM auto-launch list - the row says "check this" instead of
 * claiming a problem. Telling someone a setting is off when it is on is how
 * an app teaches people to ignore it.
 */
@Composable
private fun PowerRow(requirement: PowerPages.Requirement, onOpen: () -> Unit) {
    val label = when (requirement.id) {
        PowerPages.ID_BATTERY_UNRESTRICTED -> stringResource(R.string.power_battery)
        PowerPages.ID_AUTO_LAUNCH -> stringResource(R.string.power_auto_launch)
        PowerPages.ID_BACKGROUND_ACTIVITY -> stringResource(R.string.power_background)
        else -> requirement.id
    }
    val state = when {
        requirement.readable && requirement.satisfied -> stringResource(R.string.power_allowed)
        requirement.readable -> stringResource(R.string.power_blocked)
        else -> stringResource(R.string.power_check)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                state,
                style = MaterialTheme.typography.bodySmall,
                color = if (requirement.readable && requirement.satisfied) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (!(requirement.readable && requirement.satisfied)) {
            OutlinedButton(onClick = onOpen) { Text(stringResource(R.string.power_open)) }
        }
    }
}

/** The exact folder(s) to pick in the cloud app - never paraphrased. */
@Composable
fun FolderPaths(mode: app.cloudsaver.core.logic.OutputMode) {
    val paths = OutputPaths.forMode(mode)
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            stringResource(
                if (paths.size == 1) R.string.folder_pick_one else R.string.folder_pick_two
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        for (path in paths) {
            Text(
                path,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * Puts the output folder on the clipboard.
 *
 * Cloud apps ask you to pick a folder by typing or browsing to it, and
 * "Pictures/CloudSaver" typed slightly wrong backs up nothing at all while
 * looking like it worked.
 */
@Composable
fun CopyPathButton(mode: app.cloudsaver.core.logic.OutputMode) {
    val paths = OutputPaths.forMode(mode)
    val copyPath = app.cloudsaver.ui.components.rememberPathCopier()
    OutlinedButton(onClick = { copyPath(paths.joinToString("\n")) }) {
        Text(
            pluralStringResource(R.plurals.copy_path, paths.size)
        )
    }
}

/** One "label - value" line in the setup summary. */
@Composable
private fun SummaryLine(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@Composable
private fun scopeSummary(
    scope: app.cloudsaver.core.logic.BackupScope,
    included: Int,
    total: Int
): String {
    val what = stringResource(
        when (scope) {
            app.cloudsaver.core.logic.BackupScope.ALL -> R.string.scope_all
            app.cloudsaver.core.logic.BackupScope.PHOTOS -> R.string.scope_photos
            app.cloudsaver.core.logic.BackupScope.VIDEOS -> R.string.scope_videos
        }
    )
    // Counted the same way the albums step counts them: what is included.
    // Saying "All, minus 2 albums" one screen after "2 albums selected" asks
    // the reader to do the subtraction and check the app's arithmetic.
    return if (total <= 0 || included >= total) {
        what
    } else {
        stringResource(R.string.onb_ready_what_albums, what, included, total)
    }
}

@Composable
private fun presetSummary(preset: app.cloudsaver.core.logic.Preset): String = stringResource(
    when (preset) {
        app.cloudsaver.core.logic.Preset.STORAGE_SAVER -> R.string.preset_storage
        app.cloudsaver.core.logic.Preset.BALANCED -> R.string.preset_balanced
        app.cloudsaver.core.logic.Preset.MAX_SAVER -> R.string.preset_max
    }
)

@Composable
private fun speedSummary(speed: app.cloudsaver.core.logic.SpeedMode): String = stringResource(
    when (speed) {
        app.cloudsaver.core.logic.SpeedMode.SMART -> R.string.speed_smart
        app.cloudsaver.core.logic.SpeedMode.CHARGING_ONLY -> R.string.speed_charging
        app.cloudsaver.core.logic.SpeedMode.FAST -> R.string.speed_fast
    }
)

/** What can honestly be promised with the chosen cloud (J1). */
@Composable
fun cloudPromiseLine(cloudId: String): String {
    val caps = app.cloudsaver.core.logic.CloudCapability.defaultsFor(cloudId)
    return when (app.cloudsaver.core.logic.CloudPromise.forCloud(cloudId, caps)) {
        app.cloudsaver.core.logic.CloudPromise.Promise.EXACT ->
            stringResource(R.string.promise_exact)
        app.cloudsaver.core.logic.CloudPromise.Promise.LEDGER_ONLY ->
            stringResource(R.string.promise_ledger)
        app.cloudsaver.core.logic.CloudPromise.Promise.UNKNOWN ->
            stringResource(R.string.promise_unknown)
    }
}

@Composable
fun linkStateLine(state: AppViewModel.LinkState): String = when (state) {
    AppViewModel.LinkState.CONNECTED -> stringResource(R.string.link_connected)
    AppViewModel.LinkState.NO_APP -> stringResource(R.string.link_no_app)
    AppViewModel.LinkState.NO_FOLDER -> stringResource(R.string.link_no_folder)
    AppViewModel.LinkState.NO_TRAFFIC -> stringResource(R.string.link_no_traffic)
    AppViewModel.LinkState.CANNOT_TELL -> stringResource(R.string.link_cannot_tell)
}

/** The one picker, shared by setup and Settings (A3). */
@Composable
fun CloudPickerDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        title = { Text(stringResource(R.string.opt_cloud)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.cloud_section_e2ee),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                for (app in CloudApps.SELECTABLE.filter { it.e2ee }) {
                    CloudPickRowSimple(app, current, onPick)
                }
                Text(
                    stringResource(R.string.cloud_section_also),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp)
                )
                for (app in CloudApps.SELECTABLE.filter { !it.e2ee }) {
                    CloudPickRowSimple(app, current, onPick)
                }
            }
        }
    )
}

@Composable
private fun CloudPickRowSimple(
    app: app.cloudsaver.data.CloudApp,
    current: String,
    onPick: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val installed = remember(app.id) { CloudApps.isAppInstalled(context, app.id) }
    TextButton(
        onClick = { onPick(app.id) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                if (app.id == current) {
                    stringResource(R.string.cloud_selected_mark, app.label)
                } else {
                    app.label
                },
                style = MaterialTheme.typography.bodyLarge
            )
            if (installed && app.packages.isNotEmpty()) {
                Text(
                    stringResource(R.string.cloud_installed_mark),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Progress as dots: the active step is a wide pill, the rest are small. */
@Composable
private fun StepDots(current: Int, total: Int) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
    ) {
        for (index in 0 until total) {
            val done = index <= current
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .size(width = if (index == current) 22.dp else 8.dp, height = 8.dp)
                    .background(
                        if (done) scheme.primary else scheme.surfaceContainerHighest,
                        CircleShape
                    )
            )
        }
    }
}

@Composable
private fun StepCard(
    title: String,
    text: String,
    buttonLabel: String,
    onButton: () -> Unit,
    onSkip: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {}
) {
    AppCard {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        Row {
            Button(onClick = onButton) { Text(buttonLabel) }
            if (onSkip != null) {
                Spacer(Modifier.padding(horizontal = 4.dp))
                TextButton(onClick = onSkip) { Text(stringResource(R.string.skip)) }
            }
        }
        extra()
    }
}
