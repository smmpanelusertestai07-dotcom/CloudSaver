package app.cloudsaver.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import app.cloudsaver.ui.theme.Dimens
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudsaver.R
import app.cloudsaver.core.logic.OnboardingSteps
import app.cloudsaver.core.logic.OnboardingSteps.Step
import app.cloudsaver.core.logic.OutputPaths
import app.cloudsaver.data.CloudApps
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.components.EmptyState
import app.cloudsaver.ui.components.WarningText
import app.cloudsaver.ui.components.AlbumGrid
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.BrandMark
import app.cloudsaver.ui.components.ListTags
import app.cloudsaver.ui.components.PasswordDialog
import app.cloudsaver.util.Formats
import app.cloudsaver.util.OemPages
import app.cloudsaver.util.PowerPages
import app.cloudsaver.util.Permissions
import app.cloudsaver.ui.components.SegmentedChoice
import app.cloudsaver.ui.components.TrialCard

/**
 * The most of the setup card the album list may take before it scrolls itself.
 *
 * The list is a lazy one, and a lazy list inside a page that already scrolls
 * has to be told how tall it may be - without a ceiling it has nothing to
 * measure against and will not draw at all. This is roughly the height of six
 * rows at an ordinary text size, so the choice above it, the running count and
 * the button below all stay on screen while the albums scroll between them.
 */
private val AlbumListMaxHeight = 320.dp

/**
 * One-time setup.
 *
 * The position of a step is stated in exactly one place - the header and the
 * dots, both read from [OnboardingSteps]. No card title carries a number, so
 * the two can no longer disagree the way "Step 6 of 7" once sat above a card
 * headed "5.".
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
    // Where setup was when it was last left, for the banner. Zero means there
    // is nothing to say, and the first tap forward puts it back to zero - see
    // goTo below, which is where the sentence stops being true.
    var resumedAt by rememberSaveable { mutableIntStateOf(0) }
    // Whether the gallery is fully readable, from the flow the view model
    // refreshes on resume rather than from a one-shot read that can only ever
    // be right at the moment the card is first drawn.
    val mediaAccess by vm.mediaAccess.collectAsStateWithLifecycle()

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
        // "Picking up where you left off" is about one card - the one setup
        // was abandoned on. It was switched on by the stored step and then
        // never switched off, so someone who came back to the battery card
        // and tapped forward carried that sentence with them through
        // notifications, usage access, the cloud step and the summary: a
        // banner announcing a return that had already happened, sitting above
        // every remaining screen of the run and pushing the actual step down
        // the page. The first tap is the moment it stops being true, so that
        // is where it is cleared.
        resumedAt = 0
        step = next.coerceIn(0, OnboardingSteps.TOTAL - 1)
        vm.setOnboardingStep(step)
    }

    fun go(next: Step) = goTo(OnboardingSteps.indexOf(next))

    /**
     * What Back means during setup, for the gesture and the button alike:
     * one card back, always. A correction from the summary no longer leaves
     * the summary at all - the album chooser opens over it as a sheet, and
     * its own dismiss is the way back - so there is no detour state for Back
     * to unwind any more.
     */
    fun backOneStep() {
        goTo(step - 1)
    }

    // Without this the system Back gesture finishes the activity from every
    // card, so someone on step seven who swipes back is thrown out of the app
    // and has to start it again. On the first card there is nothing behind
    // setup, so leaving is the right answer and the default is left alone.
    BackHandler(enabled = step > 0) { backOneStep() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        go(
            if (Permissions.mediaAccess(context) == Permissions.MediaAccess.FULL) {
                Step.ALBUMS
            } else {
                Step.MEDIA
            }
        )
    }

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
            // Setup is drawn outside the Scaffold, so nothing was applying the
            // system bar insets for it and nothing was capping its width. On a
            // phone held upright neither showed: a 28 dp spacer happened to
            // clear the status bar and no phone is wider than the cap. Turned
            // sideways, the last control on the card sat underneath the
            // navigation buttons; on a tablet setup ran the full width of the
            // glass while every screen after it stopped at a column. The first
            // screen anyone sees was the one screen not following the rules.
            .safeDrawingPadding()
            .wrapContentWidth()
            .widthIn(max = Dimens.ContentMaxWidth)
            .verticalScroll(rememberScrollState())
            .padding(Dimens.Screen)
    ) {
        // No spacer here any more. The 28 dp that used to sit at the top was
        // standing in for the status bar before safeDrawingPadding was
        // applied above, and with the real inset in place it was simply 28 dp
        // of nothing - which on a phone turned sideways, where the whole card
        // has 480 dp of height to live in, is a line of text's worth of the
        // screen spent on a gap that no longer holds anything back.
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(size = 44.dp)
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                // The name is set in the largest style the app owns, so at
                // the biggest accessibility font on a 320 dp phone it needs
                // most of the line. Weighted, it wraps inside what is left
                // beside the mark instead of measuring itself first and
                // pushing the row wider than the screen.
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
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
                // What it is, before it is tapped: "Restore from a backup
                // file" on a first-run screen reads like a step everyone is
                // supposed to take, and picking the wrong file is the only
                // way this screen can go wrong.
                Text(
                    stringResource(R.string.onb0_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                transferMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            Step.MEDIA -> {
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshHealth() }
                val access = mediaAccess
                StepCard(
                    title = stringResource(R.string.onb1_title),
                    text = stringResource(R.string.onb1_text),
                    buttonLabel = when (access) {
                        Permissions.MediaAccess.FULL -> stringResource(R.string.onb_done_next)
                        Permissions.MediaAccess.PARTIAL ->
                            stringResource(R.string.partial_action)
                        Permissions.MediaAccess.NONE -> stringResource(R.string.onb1_grant)
                    },
                    onButton = {
                        when (access) {
                            Permissions.MediaAccess.FULL -> go(Step.ALBUMS)
                            // "Select photos" was chosen. Asking again shows
                            // the same picker; only the app's settings page
                            // can raise the level to full.
                            Permissions.MediaAccess.PARTIAL ->
                                OemPages.openAppInfo(context)
                            Permissions.MediaAccess.NONE -> permissionLauncher.launch(
                                Permissions.mediaPermissionsToRequest()
                            )
                        }
                    }
                ) {
                    if (access == Permissions.MediaAccess.PARTIAL) {
                        Text(
                            stringResource(R.string.partial_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    TextButton(onClick = { OemPages.openAppInfo(context) }) {
                        Text(stringResource(R.string.onb1_appinfo))
                    }
                }
            }

            Step.ALBUMS -> {
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadBuckets() }
                val buckets by vm.buckets.collectAsStateWithLifecycle()
                val albums by vm.albums.collectAsStateWithLifecycle()
                val bucketsLoaded by vm.bucketsLoaded.collectAsStateWithLifecycle()
                val locked by vm.lockedBuckets.collectAsStateWithLifecycle()
                val included = buckets.count { it !in options.excludedBuckets }
                // How much this choice actually involves. Re-measured on every
                // tick, off the main thread, and simply absent until it has
                // been measured - a zero would read as an empty gallery.
                val ticked by vm.selectedAlbumBytes.collectAsStateWithLifecycle()
                androidx.compose.runtime.LaunchedEffect(options.excludedBuckets, buckets) {
                    vm.refreshSelectedAlbumBytes()
                }
                StepCard(
                    title = stringResource(R.string.onb_albums_title),
                    text = stringResource(R.string.onb_albums_text),
                    // No Skip. Everything else here is a permission the app can
                    // work without; this is the list of someone's photos.
                    buttonLabel = stringResource(R.string.onb_albums_confirm),
                    onButton = { go(Step.NOTIFICATIONS) },
                    buttonAtEnd = true
                ) {
                    // Empty has two meanings and they are not the same
                    // sentence. Until the scan has answered, this is a wait.
                    // Once it has answered with nothing, the phone genuinely
                    // has no photos on it - and saying "Loading albums..."
                    // then is a wait that never ends.
                    if (buckets.isEmpty()) {
                        if (bucketsLoaded) {
                            EmptyState(
                                title = stringResource(R.string.folders_none_title),
                                body = stringResource(R.string.folders_none_body)
                            )
                        } else {
                            Text(
                                stringResource(R.string.folders_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // The running count and the select-all control cannot
                    // share a line on a narrow phone at a large font: the
                    // button carries no weight, so in a plain Row it was
                    // measured first and left "12 albums selected" a word
                    // wide down the left edge. Flowing, the button drops
                    // underneath the count and both are readable in full.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            // "0 albums selected" is a zero standing where a
                            // sentence should be; before anything is ticked
                            // the state has a name instead.
                            if (included == 0) {
                                stringResource(R.string.onb_albums_none_yet)
                            } else {
                                pluralStringResource(
                                    R.plurals.onb_albums_selected, included, included
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
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
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    // Absent until something is ticked: "0 MB in the ticked
                    // albums" under "Nothing ticked yet" said the same nothing
                    // twice, once as a figure.
                    if (included > 0) ticked?.let { bytes ->
                        Text(
                            stringResource(R.string.onb_albums_size, Formats.bytes(bytes)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // The gallery, drawn as a gallery: covers with the tick on
                    // the photo, through the same grid Settings uses. Lazy and
                    // capped for the same reasons the old list was - hundreds
                    // of albums are an ordinary gallery, and a lazy container
                    // inside this page's own scroll needs a ceiling to measure
                    // against at all.
                    AlbumGrid(
                        albums = albums,
                        excluded = options.excludedBuckets,
                        onToggle = { name, include ->
                            vm.setExcludedBuckets(
                                if (include) options.excludedBuckets - name
                                else options.excludedBuckets + name
                            )
                        },
                        maxHeight = AlbumListMaxHeight,
                        testTag = ListTags.ROWS
                    )
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
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis
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

            // The only step whose primary action leaves the app instead of
            // moving on, because usage access can only be granted on the
            // system page. It used to carry three controls - the grant, a
            // "Skip", and a second filled button that did exactly what Skip
            // did - so two buttons of equal weight sat side by side and
            // neither was obviously the way forward. One filled button for
            // the thing to do, one text button for carrying on afterwards.
            Step.USAGE -> StepCard(
                title = stringResource(R.string.onb4_title),
                text = stringResource(R.string.onb4_text),
                buttonLabel = stringResource(R.string.onb4_grant),
                onButton = { OemPages.openUsageAccess(context) }
            ) {
                TextButton(
                    onClick = { go(Step.CLOUD) },
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Text(stringResource(R.string.onb_done_next))
                }
            }

            Step.CLOUD -> {
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.detectAndPersistCloud() }
                val detection by vm.cloudDetection.collectAsStateWithLifecycle()
                val link by vm.linkState.collectAsStateWithLifecycle()
                // Saveable, not remembered: turning the phone sideways
                // while the cloud picker is open used to close it and drop
                // the choice half-made.
                var picking by rememberSaveable { mutableStateOf(false) }
                val chosen = detection.chosen
                StepCard(
                    title = stringResource(R.string.onb5_title),
                    text = stringResource(R.string.cloud_intended),
                    // The primary action is last, after everything the user
                    // has to read and do. Continuing past the double-backup
                    // card is the acknowledgement Z5.2 asks to record - the
                    // card was on screen, unconditionally, before this button.
                    buttonLabel = stringResource(R.string.onb_done_next),
                    onButton = {
                        vm.acknowledgeDoubleBackup()
                        go(Step.READY)
                    }
                ) {
                    Text(
                        stringResource(R.string.onb5_found, chosen.label),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    // Z5.1: the one mistake that silently doubles a cloud
                    // bill - the cloud app backing up Camera AND the
                    // CloudSaver folder. Shown always, even when that app's
                    // own folder list cannot be read.
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(Dimens.ControlCorner),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                stringResource(R.string.double_backup_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                stringResource(R.string.double_backup_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            if (chosen.packages.isNotEmpty()) {
                                TextButton(onClick = { CloudApps.launch(context, chosen.id) }) {
                                    Text(stringResource(R.string.double_backup_show))
                                }
                            }
                        }
                    }
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
                    // Three buttons that used to be three Column children
                    // with nothing between them, so they touched. Flowing
                    // them puts a gap between the three and lets them share a
                    // line where there is room for it, while on a narrow
                    // phone at a large font each still gets a line of its
                    // own. "Open %s" carries a cloud app's name, which is as
                    // long as that company chose to make it.
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (chosen.packages.isNotEmpty()) {
                            OutlinedButton(onClick = {
                                CloudApps.launch(context, chosen.id)
                                vm.clearLinkState()
                            }) {
                                Text(
                                    stringResource(R.string.onb5_open, chosen.label),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        OutlinedButton(onClick = { vm.verifyCloudLink() }) {
                            Text(
                                stringResource(R.string.onb5_check),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(onClick = { picking = true }) {
                            Text(
                                stringResource(R.string.cloud_use_different),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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
                // Correcting the albums opens the chooser here, over the
                // summary, as a sheet. The old way walked back to step 3 and
                // the page read as five steps of progress lost.
                var choosingAlbums by rememberSaveable { mutableStateOf(false) }
                SummaryLine(
                    stringResource(R.string.onb_ready_what),
                    scopeSummary(options.scope, includedAlbums, allAlbums.size)
                )
                // Every album excluded means "Start backing up" would do
                // nothing, for ever, with no error - the worst possible
                // outcome of a setup screen. Say it here, where it can still
                // be fixed in one tap.
                if (allAlbums.isNotEmpty() && includedAlbums == 0) {
                    WarningText(stringResource(R.string.onb_ready_no_albums))
                    TextButton(onClick = { choosingAlbums = true }) {
                        Text(stringResource(R.string.onb_ready_pick_albums))
                    }
                }
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
                val writableVolumes by vm.writableVolumes.collectAsStateWithLifecycle()
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshVolumes() }
                val offerableVolumes = volumes.filter {
                    it.isPrimary || it.mediaVolumeName in writableVolumes
                }
                if (offerableVolumes.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.onb_ready_where),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    SegmentedChoice(
                        offerableVolumes.map { vol ->
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
                    onRun = { vm.startTestRun() },
                    // Nothing has been scanned during setup, so the waiting
                    // count is zero on a perfectly normal phone. What the
                    // trial actually needs is an album to read.
                    albumsChosen = includedAlbums > 0,
                    // The warning above already offers this when no album is
                    // ticked, next to the sentence explaining why. Two
                    // identical buttons in one card is one too many.
                    onChooseAlbums = if (includedAlbums == 0 && allAlbums.isNotEmpty()) {
                        null
                    } else {
                        { choosingAlbums = true }
                    },
                    accessFull = mediaAccess == Permissions.MediaAccess.FULL
                )

                if (choosingAlbums) {
                    ModalBottomSheet(
                        onDismissRequest = { choosingAlbums = false },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    ) {
                        Column {
                            // The grid takes what the button leaves. Done sat
                            // inside the grid's footer once, which on a short
                            // screen put it below the fold of the grid's own
                            // scroll - a finish button that had to be found.
                            // Pinned here it is always on screen, and the
                            // weight is the ceiling the lazy grid measures
                            // against when the screen is shorter than it is.
                            AlbumChooserSheetBody(
                                vm,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Button(
                                onClick = { choosingAlbums = false },
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .padding(horizontal = Dimens.Screen)
                                    .padding(top = 4.dp, bottom = 20.dp)
                            ) {
                                Text(
                                    stringResource(R.string.albums_sheet_done),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        if (step > 0) {
            TextButton(onClick = { backOneStep() }) {
                Text(stringResource(R.string.back))
            }
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
@OptIn(ExperimentalLayoutApi::class)
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
    // "Battery: no restrictions" and a button beside it is more than a
    // 320 dp phone at the largest accessibility font can fit on one line, and
    // the button carries no weight - a plain Row measured it first and left
    // the setting's name a single word wide. Flowing, the button drops under
    // the name it belongs to rather than squeezing it.
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column(Modifier.padding(end = 12.dp)) {
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
            OutlinedButton(onClick = onOpen) {
                Text(
                    stringResource(R.string.power_open),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
    // Z10.2: the folder is visible in the gallery, unavoidably - the cloud
    // app can only back up a folder MediaStore exposes. Said here, where the
    // path is, so nobody discovers the album and thinks something leaked.
    // Never a .nomedia file: it would hide the folder from the cloud app too.
    Text(
        stringResource(R.string.folder_gallery_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
}

/** One "label - value" line in the setup summary. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryLine(label: String, value: String) {
    // Neither half is weighted any more, because a weighted pair divides the
    // line whether or not either share is enough.
    //
    // The value is unbounded - "Photos and videos, from 2 of your 5 albums"
    // beside "What's backed up" - and at the largest accessibility font on a
    // 320 dp phone a fixed 0.8-to-1.2 split left the label a single word wide
    // down the left edge while the value was still wrapping to four lines
    // next to it. Flowing, they share a line whenever a line will hold them,
    // which is every ordinary phone at an ordinary font, and where it will
    // not the value moves underneath its own label and both are read in full.
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.padding(start = 12.dp)
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
        pluralStringResource(R.plurals.onb_ready_what_albums, total, what, included, total)
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
    // Whether this cloud app is on the phone, asked again every time the app
    // comes back to the foreground.
    //
    // Asked once and remembered for the life of the row, the answer went stale
    // in the one situation this picker creates: the list says an app is not
    // installed, the reader leaves to install it, comes back to a picker that
    // was never closed - and the row still says nothing, because the question
    // was only ever asked before they left. The package manager is asked once
    // per row per return to the app, which is nothing, and never on a frame of
    // scrolling.
    var installed by remember(app.id) {
        mutableStateOf(CloudApps.isAppInstalled(context, app.id))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        installed = CloudApps.isAppInstalled(context, app.id)
    }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepCard(
    title: String,
    text: String,
    buttonLabel: String,
    onButton: () -> Unit,
    onSkip: (() -> Unit)? = null,
    // Most steps are a sentence with a decision under it, so the action sits
    // right after the text. The album step is a picker: the decision is the
    // grid, and the action belongs after the choosing, not above it.
    buttonAtEnd: Boolean = false,
    extra: @Composable () -> Unit = {}
) {
    AppCard {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        if (buttonAtEnd) {
            extra()
            Spacer(Modifier.height(12.dp))
        }
        // The action and its Skip are the pair that breaks first: "Grant
        // usage access" and "Skip" cannot share a line on a 320 dp phone at
        // the largest accessibility font, and a plain Row pushed Skip past
        // the edge of the card, where the one way past the step was
        // unreachable. Flowing, Skip drops underneath instead.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onButton) {
                Text(buttonLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onSkip != null) {
                TextButton(onClick = onSkip) {
                    Text(
                        stringResource(R.string.skip),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (!buttonAtEnd) extra()
    }
}

/**
 * The album chooser as a sheet over the summary. Correcting the ticks is a
 * correction, not a journey: the chooser opens where the question arose, and
 * closing it lands exactly where the user already was, with nothing walked
 * back through.
 *
 * One bounded grid does all the scrolling. A sheet measures its content with
 * no ceiling of its own, so the count row, the size line, the locked folders
 * and the Done button ride inside the grid as full-width rows - outside it
 * they would sit past the edge of a short screen with no way to reach them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlbumChooserSheetBody(vm: AppViewModel, modifier: Modifier = Modifier) {
    val options by vm.options.collectAsStateWithLifecycle()
    val buckets by vm.buckets.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val bucketsLoaded by vm.bucketsLoaded.collectAsStateWithLifecycle()
    val locked by vm.lockedBuckets.collectAsStateWithLifecycle()
    val ticked by vm.selectedAlbumBytes.collectAsStateWithLifecycle()
    val included = buckets.count { it !in options.excludedBuckets }
    LaunchedEffect(Unit) { vm.loadBuckets() }
    LaunchedEffect(options.excludedBuckets, buckets) { vm.refreshSelectedAlbumBytes() }

    if (buckets.isEmpty()) {
        // The same two meanings of empty the album step distinguishes: still
        // asking, or truly nothing. Static content, so the sheet can measure
        // it without a scroller.
        Column(
            modifier
                .padding(horizontal = Dimens.Screen)
                .padding(bottom = 8.dp)
        ) {
            if (bucketsLoaded) {
                EmptyState(
                    title = stringResource(R.string.folders_none_title),
                    body = stringResource(R.string.folders_none_body)
                )
            } else {
                Text(
                    stringResource(R.string.folders_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    AlbumGrid(
        albums = albums,
        excluded = options.excludedBuckets,
        onToggle = { name, include ->
            vm.setExcludedBuckets(
                if (include) options.excludedBuckets - name
                else options.excludedBuckets + name
            )
        },
        maxHeight = AlbumListMaxHeight,
        modifier = modifier.padding(horizontal = Dimens.Screen),
        testTag = ListTags.ROWS,
        header = {
            Column {
                Text(
                    stringResource(R.string.onb_albums_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        if (included == 0) {
                            stringResource(R.string.onb_albums_none_yet)
                        } else {
                            pluralStringResource(
                                R.plurals.onb_albums_selected, included, included
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(
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
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (included > 0) ticked?.let { bytes ->
                    Text(
                        stringResource(R.string.onb_albums_size, Formats.bytes(bytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        footer = {
            Column {
                if (locked.isNotEmpty()) {
                    Text(
                        stringResource(R.string.folders_auto_excluded),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    for ((bucket, _) in locked) {
                        Text(
                            bucket,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis
                        )
                    }
                }
            }
        }
    )
}
