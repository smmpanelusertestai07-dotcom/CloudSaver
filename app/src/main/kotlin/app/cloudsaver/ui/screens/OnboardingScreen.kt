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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudsaver.R
import app.cloudsaver.core.logic.OnboardingSteps
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
    var step by remember {
        mutableIntStateOf(options.onboardingStep.coerceIn(0, OnboardingSteps.TOTAL - 1))
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    fun goTo(next: Int) {
        step = next.coerceIn(0, OnboardingSteps.TOTAL - 1)
        vm.setOnboardingStep(step)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { goTo(if (Permissions.hasMediaRead(context)) 2 else 1) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { goTo(3) }

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
        when (shown) {
            0 -> StepCard(
                title = stringResource(R.string.onb0_title),
                text = stringResource(R.string.onb0_text),
                buttonLabel = stringResource(R.string.onb_start),
                onButton = { goTo(1) }
            ) {
                TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                    Text(stringResource(R.string.onb0_import))
                }
                transferMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            1 -> StepCard(
                title = stringResource(R.string.onb1_title),
                text = stringResource(R.string.onb1_text),
                buttonLabel = if (Permissions.hasMediaRead(context)) {
                    stringResource(R.string.onb_done_next)
                } else {
                    stringResource(R.string.onb1_grant)
                },
                onButton = {
                    if (Permissions.hasMediaRead(context)) goTo(2)
                    else permissionLauncher.launch(Permissions.mediaPermissionsToRequest())
                }
            ) {
                TextButton(onClick = { OemPages.openAppInfo(context) }) {
                    Text(stringResource(R.string.onb1_appinfo))
                }
            }

            2 -> StepCard(
                title = stringResource(R.string.onb2_title),
                text = stringResource(R.string.onb2_text),
                buttonLabel = stringResource(R.string.onb2_grant),
                onButton = {
                    if (android.os.Build.VERSION.SDK_INT >= 33 && !Permissions.hasNotifications(context)) {
                        notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        goTo(3)
                    }
                },
                onSkip = { goTo(3) }
            )

            3 -> {
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshPowerRequirements() }
                val requirements by vm.powerRequirements.collectAsStateWithLifecycle()
                StepCard(
                    title = stringResource(R.string.onb3_title),
                    text = stringResource(R.string.onb3_text),
                    buttonLabel = stringResource(R.string.onb_done_next),
                    onButton = { goTo(4) },
                    onSkip = { goTo(4) }
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

            4 -> StepCard(
                title = stringResource(R.string.onb4_title),
                text = stringResource(R.string.onb4_text),
                buttonLabel = stringResource(R.string.onb4_grant),
                onButton = { OemPages.openUsageAccess(context) },
                onSkip = { goTo(5) }
            ) {
                Button(onClick = { goTo(5) }, modifier = Modifier.padding(top = 6.dp)) {
                    Text(stringResource(R.string.onb_done_next))
                }
            }

            5 -> {
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
                    onButton = { goTo(6) }
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

            6 -> StepCard(
                title = stringResource(R.string.onb6_title),
                text = stringResource(R.string.onb6_text),
                buttonLabel = if (testRunning) {
                    stringResource(R.string.onb6_running)
                } else {
                    stringResource(R.string.onb6_run)
                },
                onButton = { if (!testRunning) vm.startTestRun() }
            ) {
                testItems?.let { list ->
                    if (list.isEmpty()) {
                        Text(
                            stringResource(R.string.onb6_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    for (item in list) {
                        KeyValueRow(
                            item.name,
                            stringResource(
                                R.string.files_size_pair,
                                Formats.bytes(item.before),
                                Formats.bytes(item.after)
                            )
                        )
                    }
                }
                Button(
                    onClick = { vm.finishOnboarding() },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text(stringResource(R.string.onb_finish)) }
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
