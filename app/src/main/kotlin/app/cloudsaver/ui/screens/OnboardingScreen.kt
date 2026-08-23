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
import app.cloudsaver.data.CloudApps
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.KeyValueRow
import app.cloudsaver.ui.components.PasswordDialog
import app.cloudsaver.util.Formats
import app.cloudsaver.util.OemPages
import app.cloudsaver.util.Permissions

/**
 * One-time onboarding: 6 step cards, one button each, re-runnable from Help.
 * Steps: media permission, notifications, battery, usage access, cloud
 * checklist, test run.
 */
@Composable
fun OnboardingScreen(vm: AppViewModel) {
    val options by vm.options.collectAsStateWithLifecycle()
    var step by remember { mutableIntStateOf(options.onboardingStep.coerceIn(0, 6)) }
    var showCalc by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    fun goTo(next: Int) {
        step = next.coerceIn(0, 6)
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
            Icon(
                painterResource(R.drawable.ic_stat_cloud),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
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
        if (showCalc) {
            Text(
                stringResource(R.string.calc_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            CalculatorContent(vm)
            Button(
                onClick = { showCalc = false },
                modifier = Modifier.padding(top = 10.dp)
            ) { Text(stringResource(R.string.back)) }
            Spacer(Modifier.height(24.dp))
            return@Column
        }
        Text(
            stringResource(R.string.onb_step_counter, step + 1, 7),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 18.dp)
        )
        StepDots(current = step, total = 7)

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

            3 -> StepCard(
                title = stringResource(R.string.onb3_title),
                text = stringResource(R.string.onb3_text),
                buttonLabel = stringResource(R.string.onb3_battery),
                onButton = { OemPages.requestIgnoreBatteryOptimizations(context) },
                onSkip = { goTo(4) }
            ) {
                OutlinedButton(onClick = {
                    if (!OemPages.openAutoStart(context)) OemPages.openAppInfo(context)
                }) { Text(stringResource(R.string.onb3_autostart)) }
                Text(
                    stringResource(R.string.onb3_fallback),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = { goTo(4) }, modifier = Modifier.padding(top = 6.dp)) {
                    Text(stringResource(R.string.onb_done_next))
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
                val detected = remember { CloudApps.detectDefault(context) }
                StepCard(
                    title = stringResource(R.string.onb5_title),
                    text = stringResource(R.string.cloud_intended),
                    buttonLabel = stringResource(R.string.onb_done_next),
                    onButton = { goTo(6) }
                ) {
                    Text(
                        stringResource(R.string.onb5_text, detected.label),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    detected.checklistRes?.let { res ->
                        Text(
                            stringResource(res),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    if (detected.packages.isNotEmpty()) {
                        OutlinedButton(onClick = { CloudApps.launch(context, detected.id) }) {
                            Text(stringResource(R.string.onb5_open, detected.label))
                        }
                    }
                    OutlinedButton(onClick = { showCalc = true }) {
                        Text(stringResource(R.string.calc_open))
                    }
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
