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
import androidx.compose.runtime.mutableIntStateOf
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
import app.cloudsaver.core.logic.Projection
import app.cloudsaver.data.CloudApps
import app.cloudsaver.util.Permissions
import app.cloudsaver.ui.goTo
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.Routes
import app.cloudsaver.ui.components.AnimatedNumber
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.BrandMark
import app.cloudsaver.ui.components.HeroCard
import app.cloudsaver.ui.components.MeterBar
import app.cloudsaver.ui.components.MetricGrid
import app.cloudsaver.ui.components.MetricTile
import app.cloudsaver.ui.components.SectionHeader
import app.cloudsaver.ui.components.StatusChip
import app.cloudsaver.util.Formats
import app.cloudsaver.util.OemPages
import app.cloudsaver.util.PowerPages
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TrendingUp
import app.cloudsaver.ui.theme.TabularFigures
import app.cloudsaver.ui.theme.OnBrand
import app.cloudsaver.ui.theme.OnBrandFaint
import app.cloudsaver.ui.theme.OnBrandMuted
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Bolt
import app.cloudsaver.core.logic.HomeAction
import app.cloudsaver.ui.components.TrialCard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.cloudsaver.ui.theme.Dimens
import app.cloudsaver.ui.theme.MetricTextStyle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: AppViewModel, nav: NavHostController) {
    val options by vm.options.collectAsStateWithLifecycle()
    val counters by vm.counters.collectAsStateWithLifecycle()
    val savedBytes by vm.savedBytes.collectAsStateWithLifecycle()
    val processed by vm.processedCount.collectAsStateWithLifecycle()
    val noAlbumsTicked by vm.noAlbumsTicked.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val confirmResult by vm.confirmResult.collectAsStateWithLifecycle()
    val leftoverUris by vm.leftoverUris.collectAsStateWithLifecycle()
    val tampered by vm.tampered.collectAsStateWithLifecycle()
    val mediaAccess by vm.mediaAccess.collectAsStateWithLifecycle()
    val crashPending by vm.crashPending.collectAsStateWithLifecycle()
    val savings by vm.savings.collectAsStateWithLifecycle()
    val budget by vm.budget.collectAsStateWithLifecycle()
    val asIs by vm.asIs.collectAsStateWithLifecycle()
    val canConfirm by vm.cloudHasFreeUp.collectAsStateWithLifecycle()
    val skipReasons by vm.skipReasons.collectAsStateWithLifecycle()
    val statusWaiting by vm.statusWaiting.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val trialSize by vm.trialSize.collectAsStateWithLifecycle()
    val testRunning by vm.testRunning.collectAsStateWithLifecycle()
    val testItems by vm.testRun.collectAsStateWithLifecycle()
    val power by vm.powerRequirements.collectAsStateWithLifecycle()
    var explain by remember { mutableStateOf<Int?>(null) }
    val projection by vm.projectedSavings.collectAsStateWithLifecycle()
    val detailKept by vm.detailKept.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    // The confirmed count, held for as long as its card is on screen.
    //
    // The count is cleared the instant OK is tapped, but the card is still
    // shrinking away at that point and was reading the live value: its last
    // frames announced a result of zero files, which is the one number that
    // would mean the check had found nothing.
    var lastConfirm by remember { mutableIntStateOf(0) }
    LaunchedEffect(confirmResult) { confirmResult?.let { lastConfirm = it } }

    LaunchedEffect(Unit) {
        vm.refreshHealth()
        vm.detectLeftoverFiles()
        vm.refreshBudget()
        vm.refreshAsIs()
        vm.refreshCloudCaps()
        vm.refreshSkipReasons()
        vm.refreshPowerRequirements()
        vm.refreshProjection()
    }

    val cleanupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        vm.onDeleteDialogResult(result.resultCode == android.app.Activity.RESULT_OK)
    }
    val nextDeleteDialog by vm.deleteIntent.collectAsStateWithLifecycle()
    LaunchedEffect(nextDeleteDialog) {
        nextDeleteDialog?.let {
            cleanupLauncher.launch(IntentSenderRequest.Builder(it).build())
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.Screen)
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

        // Z10.1: the cloud app changed. Where the already-sent files live is
        // the one fact a switch quietly breaks, so it is said once, plainly.
        // Both names, and only when they are actually two different apps. The
        // sentence names where files went and where they did not; with one app
        // in both halves it reads "sent to X are stored there, not in X", which
        // is worse than saying nothing.
        val switchedFrom = CloudApps.byId(options.cloudSwitchFrom).label
        val switchedTo = CloudApps.byId(options.cloudSingle).label
        if (options.cloudSwitchFrom.isNotEmpty() && switchedFrom != switchedTo) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { vm.dismissCloudSwitchNotice() },
                title = { Text(stringResource(R.string.cloud_switch_title)) },
                text = {
                    // A dialog's text slot does not scroll. On a small screen at a
                    // large font its lower half simply sits past the edge, and the
                    // buttons are pushed off with it.
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.cloud_switch_body, switchedFrom, switchedTo))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.dismissCloudSwitchNotice() }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            )
        }

        // Z10.6: the whole chain, proven or stalled, as one card each - the
        // first confirmation is the moment setup stops being a hope, and 48
        // silent hours is the moment it needs naming, with the two causes.
        if (options.firstChainState == "SUCCESS" || options.firstChainState == "STALLED") {
            val success = options.firstChainState == "SUCCESS"
            AppCard(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    if (success) {
                        stringResource(
                            R.string.chain_success_title,
                            CloudApps.byId(options.cloudSingle).label
                        )
                    } else {
                        stringResource(R.string.chain_stalled_title)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (success) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                Text(
                    if (success) {
                        stringResource(R.string.chain_success_body)
                    } else {
                        stringResource(
                            R.string.chain_stalled_body,
                            CloudApps.byId(options.cloudSingle).label
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                // Two buttons on one line is two buttons on one line only
                // while they both fit. On a 320 dp phone at the largest font
                // size they do not, and the second one was pushed past the
                // card's edge - so they wrap onto a second line instead.
                FlowRow {
                    if (!success) {
                        TextButton(onClick = {
                            CloudApps.launch(context, options.cloudSingle)
                        }) { Text(stringResource(R.string.chain_open_checklist)) }
                    }
                    TextButton(onClick = { vm.dismissFirstChainNotice() }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            }
        }

        // BB3.2: the app died last time. One plain card, once - "nothing was
        // lost" is true because every state change is committed before it is
        // reported - with the trace behind the Share button on the logs page.
        AnimatedVisibility(
            visible = crashPending,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AppCard(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    stringResource(R.string.crash_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.crash_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                FlowRow {
                    TextButton(onClick = {
                        vm.dismissCrashNotice()
                        nav.goTo(Routes.HELP_LOGS)
                    }) { Text(stringResource(R.string.crash_share)) }
                    TextButton(onClick = { vm.dismissCrashNotice() }) {
                        Text(stringResource(R.string.dismiss))
                    }
                }
            }
        }

        // BB1.3: under partial access the app refuses to scan, so the truth
        // the rest of this screen usually tells is suspended. The card says
        // so and offers the one way out.
        AnimatedVisibility(
            visible = mediaAccess == Permissions.MediaAccess.PARTIAL,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AppCard(modifier = Modifier.padding(top = 8.dp)) {
                // The icon belongs beside the first line of the heading, not
                // beside the middle of it. "CloudSaver can only see the photos
                // you picked" is three lines at the largest font, and centred
                // against three lines the icon ends up opposite the second one,
                // with blank space above and below it. That reads as something
                // having slipped out of place rather than as the marker for
                // this warning, so the row is top-aligned instead.
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.partial_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    stringResource(R.string.partial_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Button(
                    onClick = { OemPages.openAppInfo(context) },
                    modifier = Modifier.padding(top = 10.dp)
                ) { Text(stringResource(R.string.partial_action)) }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Savings,
                    contentDescription = null,
                    tint = OnBrandMuted,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.hero_saved_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = OnBrandMuted
                )
            }
            AnimatedNumber(
                value = Formats.bytes(savedBytes),
                style = heroFigureStyle(),
                color = OnBrand,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                pluralStringResource(R.plurals.hero_saved_sub, processed, processed),
                style = MaterialTheme.typography.bodyMedium,
                color = OnBrandMuted
            )
            // Photos and videos side by side, and only the halves that
            // actually happened. A card that reads "Videos 0 MB" is telling
            // someone about a thing that did not occur.
            if (savings.totalBytes > 0) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (savings.photoBytes > 0) {
                        HeroStat(
                            Icons.Outlined.PhotoLibrary,
                            stringResource(R.string.scope_photos),
                            Formats.bytes(savings.photoBytes),
                            Modifier.weight(1f)
                        )
                    }
                    if (savings.videoBytes > 0) {
                        HeroStat(
                            Icons.Outlined.Movie,
                            stringResource(R.string.scope_videos),
                            Formats.bytes(savings.videoBytes),
                            Modifier.weight(1f)
                        )
                    }
                }
            }
            // The cost side of the same story. Space saved on its own invites
            // "yes, but what did it cost me?", and this is the measured answer
            // rather than a reassuring adjective.
            detailKept?.let { k ->
                Spacer(Modifier.height(12.dp))
                // Top-aligned for the same reason as the warning above: this
                // sentence wraps onto two or three lines at a large font, and
                // a 16 dp icon centred against them sits opposite a gap rather
                // than opposite the words it introduces.
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.HighQuality,
                        contentDescription = null,
                        tint = OnBrandMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.quality_kept_overall, k.percent),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnBrandMuted
                    )
                }
            }
            // What finishing the queue would be worth. Labelled an estimate
            // whenever it leans on typical ratios rather than this phone's.
            if (projection.savedBytes > 0) {
                Spacer(Modifier.height(12.dp))
                // Two stacked sentences beside one small icon, so this is the
                // worst of the centred rows: at a large font the block is four
                // or five lines tall and the icon was drawn halfway down it,
                // level with nothing in particular.
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.TrendingUp,
                        contentDescription = null,
                        tint = OnBrandMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            stringResource(
                                if (projection.isEstimate) R.string.hero_projection_estimate
                                else R.string.hero_projection,
                                Formats.bytes(projection.savedBytes)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnBrandMuted
                        )
                        // "the rest" is not a quantity. Saying how many files
                        // it covers - split by kind when both are waiting -
                        // turns the projection into something checkable.
                        queueBreakdown(projection)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnBrandMuted
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            androidx.compose.animation.Crossfade(
                targetState = statusLine(
                    options,
                    statusWaiting,
                    heldBack = counters.heldBack,
                    inFolder = counters.inFolder,
                    noAlbumsTicked = noAlbumsTicked,
                    processed = processed
                ),
                label = "statusLine"
            ) { line ->
                // Nothing counted yet, so the line has nothing to say. An
                // empty Text still takes a line of height inside the banner,
                // which reads as a gap someone forgot to fill.
                if (line.isNotEmpty()) {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = OnBrand
                    )
                }
            }
            // Nothing has run yet, so there is no time to state. A line
            // reading "Last checked -" is worse than no line.
            if (options.lastRunAt > 0) {
                // A date and a time together are long enough to wrap on a
                // narrow phone at a large font, and the clock icon then sat
                // between the two lines instead of against the first.
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = OnBrandFaint,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.last_run, Formats.dateTime(options.lastRunAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnBrandFaint
                    )
                }
            }
        }

        // Things that need the user's attention.
        // Only what Android will actually confirm. Auto-launch and background
        // activity cannot be read on any of these skins, so a chip for them
        // would nag someone who has already turned them on - and an app that
        // cries wolf is one you stop reading. They are asked for once during
        // setup; here, only real evidence speaks: nothing has run for two days.
        val anyPower = power.any { it.readable && !it.satisfied }
        // Every chip below has to be named here too. A missing storage card
        // was not, so a phone whose only problem was the removed card drew no
        // section at all and its chip - the one thing that says the pause is
        // temporary rather than the app having died - could never appear.
        val anyHealth = health.paused || anyPower || health.usageAccessOff ||
            health.cloudMissing || health.cloudNone || health.spaceLow || health.volumeMissing ||
            health.backgroundWorkStopped || options.foreignFiles > 0
        AnimatedVisibility(
            visible = anyHealth,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                SectionHeader(stringResource(R.string.section_attention))
                // Wrapping, not a scrolling strip.
                //
                // These chips are the only notice that something needs doing,
                // and in a horizontally scrolling row everything past the
                // first two sat off the edge with nothing on screen to say it
                // was there. At a large font on a narrow phone that is most of
                // them - and a warning nobody scrolls to is a warning nobody
                // gets. Wrapping puts every chip on screen at any width.
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (health.paused) {
                        StatusChip(stringResource(R.string.chip_paused)) {
                            nav.goTo(Routes.OPTIONS)
                        }
                    }
                    if (anyPower) {
                        StatusChip(stringResource(R.string.chip_battery)) {
                            vm.openPowerPage(PowerPages.ID_BATTERY_UNRESTRICTED)
                        }
                    }
                    if (health.usageAccessOff) {
                        StatusChip(stringResource(R.string.chip_usage)) {
                            OemPages.openUsageAccess(context)
                        }
                    }
                    // One chip, and it says which of the two things is
                    // actually true. A second chip for "no cloud app at all"
                    // carried the very same four words as this one, so a
                    // phone with nothing installed - the common case - drew
                    // the identical chip twice.
                    if (health.cloudMissing || health.cloudNone) {
                        StatusChip(
                            stringResource(
                                if (health.cloudNone) R.string.chip_cloud
                                else R.string.chip_cloud_gone
                            )
                        ) {
                            nav.goTo(Routes.OPTIONS)
                        }
                    }
                    if (health.spaceLow) {
                        StatusChip(stringResource(R.string.chip_space)) {
                            nav.goTo(Routes.STORAGE)
                        }
                    }
                    // Z3.3: the chosen card is gone; state is kept and work
                    // resumes by itself when it returns - the chip is so the
                    // pause never reads as the app having quietly died.
                    if (health.volumeMissing) {
                        StatusChip(stringResource(R.string.chip_volume_missing)) {
                            nav.goTo(Routes.STORAGE)
                        }
                    }
                    // The phone stopped running us. Nothing else on this row
                    // would show it, and the app would just look idle.
                    if (health.backgroundWorkStopped) {
                        StatusChip(stringResource(R.string.chip_stopped)) {
                            vm.openPowerPage(PowerPages.ID_BATTERY_UNRESTRICTED)
                        }
                    }
                    // Something is in the upload folder that CloudSaver did
                    // not put there. Never touched - the FAQ explains what
                    // happens to it instead.
                    if (options.foreignFiles > 0) {
                        StatusChip(stringResource(R.string.chip_foreign)) {
                            nav.goTo(Routes.HELP_FAQ)
                        }
                    }
                }
            }
        }

        SectionHeader(stringResource(R.string.section_progress))
        // Only the counts that have something to say, each in an identical
        // cell. A tile reading "0" is a question with no answer - but a
        // section with no tiles at all is worse, so the empty case says so
        // in a sentence below.
        val progressTiles = buildList<@Composable (Modifier) -> Unit> {
            if (counters.waiting > 0) add { m: Modifier ->
                MetricTile(
                    Formats.count(counters.waiting),
                    stringResource(R.string.count_waiting),
                    m,
                    onClick = { explain = R.string.explain_waiting },
                    icon = Icons.Outlined.Schedule
                )
            }
            if (counters.inFolder > 0) add { m: Modifier ->
                MetricTile(
                    Formats.count(counters.inFolder),
                    stringResource(R.string.count_in_folder),
                    m,
                    onClick = { explain = R.string.explain_in_folder },
                    icon = Icons.Outlined.CloudUpload
                )
            }
            // Always shown, even at zero: this is the goal, and a dashboard
            // that hides the goal until it is met explains nothing.
            add { m: Modifier ->
                MetricTile(
                    Formats.count(counters.confirmed),
                    stringResource(R.string.count_confirmed),
                    m,
                    highlight = counters.confirmed > 0,
                    onClick = { explain = R.string.explain_backed_up },
                    icon = Icons.Outlined.CloudDone
                )
            }
            if (counters.skipped > 0) add { m: Modifier ->
                MetricTile(
                    Formats.count(counters.skipped),
                    stringResource(R.string.count_skipped),
                    m,
                    onClick = {
                        vm.filesState.value = "SKIP"
                        nav.goTo(Routes.FILES)
                    },
                    icon = Icons.Outlined.RemoveCircleOutline
                )
            }
        }
        MetricGrid(progressTiles)
        if (counters.waiting == 0 && counters.inFolder == 0 && counters.confirmed == 0) {
            Text(
                stringResource(
                    if (options.lastRunAt == 0L) R.string.progress_none_yet
                    else R.string.progress_all_clear
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        // Duplicates get one quiet line, not a tile and never the Skipped
        // count: the file was handled once, under its identical twin.
        if (counters.duplicates > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    // A row that can be tapped has to be big enough to tap.
                    // A single line of small text and 8 dp of padding is
                    // about 34 dp, well under the target.
                    .heightIn(min = Dimens.TouchTarget)
                    .clip(RoundedCornerShape(Dimens.ControlCorner))
                    .clickable { nav.goTo(Routes.DUPLICATES) }
                    .padding(vertical = 8.dp)
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    pluralStringResource(
                        R.plurals.duplicates_handled, counters.duplicates, counters.duplicates
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // Skipped files, one reason per row.
        //
        // These were chips in a horizontally scrolling row, so a reason of any
        // length was sliced off at the screen edge - "1 - You askec" - which
        // reads as a rendering fault rather than as a sentence the reader is
        // meant to finish. They are facts, not filters, so they are rows: full
        // width, wrapping, with the icon that says what they are.
        if (counters.skipped > 0 && skipReasons.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            for ((reason, count) in skipReasons) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.TouchTarget)
                        .clip(RoundedCornerShape(Dimens.ControlCorner))
                        .clickable {
                            vm.filesState.value = "SKIP"
                            nav.goTo(Routes.FILES)
                        }
                        .padding(vertical = 8.dp)
                ) {
                    Icon(
                        Icons.Outlined.RemoveCircleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        pluralStringResource(
                            R.plurals.skipped_reason_row, count, count, skipReasonLabel(reason)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // The trial, until there is something real to look at instead.
        //
        // Skipping it during setup used to lose it for good, and it is the
        // cheapest answer to "what will this actually do to my photos". Once
        // files have genuinely been optimised the Files screen shows every
        // before-and-after there is, so the trial stops being offered rather
        // than sitting there forever proving something already proven.
        //
        // The offer depends on an album being ticked, not on the waiting
        // count: the run scans first, so a freshly set-up phone with nothing
        // scanned yet can still try it - and a phone with no album chosen is
        // told that, here, rather than being shown a button that would do
        // nothing.
        if (processed == 0) {
            // Reading the album list means enumerating the gallery, so it is
            // done here - once, and only while the trial card can appear -
            // rather than on every visit to Home.
            LaunchedEffect(Unit) { vm.loadBuckets() }
            val trialAlbums by vm.buckets.collectAsStateWithLifecycle()
            Spacer(Modifier.height(14.dp))
            TrialCard(
                size = trialSize,
                running = testRunning,
                results = testItems,
                onRun = { vm.startTestRun() },
                albumsChosen = trialAlbums.isEmpty() ||
                    trialAlbums.any { it !in options.excludedBuckets },
                onChooseAlbums = { nav.goTo(Routes.OPTIONS) },
                accessFull = mediaAccess == Permissions.MediaAccess.FULL
            )
        }

        // Today's upload allowance, and when it refills. Without this, an app
        // that is deliberately holding files back looks like an app that has
        // quietly stopped.
        if (!budget.unlimited && budget.totalBytes > 0) {
            Spacer(Modifier.height(14.dp))
            AppCard {
                // Title at one end, the used-of-total figure at the other -
                // until they cannot share a line, and then the figure drops
                // underneath rather than squeezing the title into a column of
                // single words. A Row could not do this: the figure carries no
                // weight, so it was measured first and took whatever it liked
                // out of the width the title was supposed to have.
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        stringResource(R.string.budget_title),
                        style = MaterialTheme.typography.titleSmall
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

        // The one action on this screen. It is offered only when a tap could
        // change something, and it never promises an upload - CloudSaver
        // optimises, the user's cloud app uploads.
        val action = HomeAction.decide(
            queued = counters.waiting,
            running = running,
            paused = options.pauseAll,
            thermalThrottled = health.thermalThrottled,
            batteryPct = health.batteryPct,
            plugged = health.plugged,
            freeBytes = health.freeBytes,
            minFreeBytes = options.minFreeBytes,
            waitReason = runCatching { RunDecider.Wait.valueOf(options.waitReason) }
                .getOrDefault(RunDecider.Wait.NONE)
        )

        Spacer(Modifier.height(16.dp))
        when (action.visibility) {
            HomeAction.Visibility.WORKING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.optimise_working),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HomeAction.Visibility.BUTTON -> {
                Button(
                    onClick = { vm.optimiseNow() },
                    enabled = action.enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Outlined.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    // The button is full width, so the label has the rest of
                    // the row to use and wraps into it. Without the weight it
                    // is measured against the whole button and pushes the bolt
                    // off the leading edge at the largest font size.
                    Text(
                        stringResource(R.string.btn_optimise_now),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                Text(
                    when (action.blocker) {
                        HomeAction.Blocker.TOO_HOT ->
                            stringResource(R.string.optimise_blocked_hot)
                        HomeAction.Blocker.BATTERY_LOW ->
                            stringResource(
                                R.string.optimise_blocked_battery,
                                HomeAction.BATTERY_FLOOR_PCT
                            )
                        HomeAction.Blocker.NOT_ENOUGH_SPACE ->
                            stringResource(R.string.optimise_blocked_space)
                        // CC7.2: when the button bypasses a scheduling rule,
                        // say which one. "Even though the schedule is waiting"
                        // is true of all of them and useful about none.
                        HomeAction.Blocker.NONE -> when (action.note) {
                            HomeAction.Note.OVERRIDES_WAITING -> {
                                val wait = runCatching {
                                    RunDecider.Wait.valueOf(options.waitReason)
                                }.getOrDefault(RunDecider.Wait.NONE)
                                stringResource(
                                    when (wait) {
                                        RunDecider.Wait.BUDGET_USED, RunDecider.Wait.PHOTO_CAP ->
                                            R.string.optimise_now_override_budget
                                        RunDecider.Wait.NOT_CHARGING ->
                                            R.string.optimise_now_override_charger
                                        RunDecider.Wait.SCREEN_ON ->
                                            R.string.optimise_now_override_screen
                                        RunDecider.Wait.BATTERY_LOW,
                                        RunDecider.Wait.BATTERY_SAVER ->
                                            R.string.optimise_now_override_battery
                                        else -> R.string.optimise_now_hint_override
                                    }
                                )
                            }
                            else -> stringResource(R.string.optimise_now_hint)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            HomeAction.Visibility.HIDDEN -> Unit
        }

        // Not a button, and not always present: this exists only where the
        // app cannot see the uploads for itself. With usage access granted
        // the check is automatic, and offering it anyway would imply the
        // automatic part does not work.
        if (HomeAction.showVerifyLink(!health.usageAccessOff, canConfirm)) {
            TextButton(onClick = { vm.startConfirmFlow() }) {
                Text(stringResource(R.string.btn_verify_link))
            }
            Text(
                stringResource(R.string.verify_link_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

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
                        lastConfirm,
                        lastConfirm
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { vm.dismissConfirmResult() }) {
                    Text(stringResource(R.string.ok))
                }
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
                onClick = { nav.goTo(Routes.FILES) }
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
            visible = leftoverUris.isNotEmpty() && !tampered,
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
                        R.plurals.old_files_text, leftoverUris.size, leftoverUris.size
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                FlowRow {
                    TextButton(onClick = {
                        val sender = vm.requestDelete(leftoverUris) { vm.onLeftoversCleaned() }
                        sender?.let {
                            cleanupLauncher.launch(IntentSenderRequest.Builder(it).build())
                        }
                    }) { Text(stringResource(R.string.old_files_clean)) }
                    TextButton(onClick = { vm.onLeftoversCleaned() }) {
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
            text = {
                // A dialog's text slot does not scroll. On a small screen at
                // a large font its lower half simply sits past the edge, and
                // the buttons are pushed off with it.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(res))
                }
            }
        )
    }
}

/**
 * The size of the one big figure in the hero banner.
 *
 * It is the largest text in the app and the only piece with nowhere to go: it
 * is drawn on a single line by design, so that a count ticking upward never
 * reflows the card. At the largest accessibility font size that line needs
 * more width than a 320 dp phone has, and the figure the whole card exists
 * for came out as "1.2..." - a shortened number says nothing at all.
 *
 * So it still grows with the reader's font setting, just not past the point
 * where it stops fitting on the narrowest phone. Every other line on this
 * screen wraps, and scales in full.
 */
@Composable
private fun heroFigureStyle(): androidx.compose.ui.text.TextStyle {
    val cap = 1.4f
    val scale = LocalDensity.current.fontScale
    if (scale <= cap) return MetricTextStyle
    val factor = cap / scale
    return MetricTextStyle.copy(
        fontSize = MetricTextStyle.fontSize * factor,
        lineHeight = MetricTextStyle.lineHeight * factor
    )
}

/**
 * What the queue projection is counting, split by kind when both apply.
 *
 * Null when nothing is waiting, so the caller shows the saving alone rather
 * than "0 files still to do" under a figure of zero.
 */
@Composable
private fun queueBreakdown(projection: Projection.Estimate): String? {
    val photos = projection.photoCount
    val videos = projection.videoCount
    return when {
        photos > 0 && videos > 0 -> stringResource(
            R.string.hero_projection_split,
            pluralStringResource(R.plurals.count_photos, photos, photos),
            pluralStringResource(R.plurals.count_videos, videos, videos)
        )
        projection.fileCount > 0 -> pluralStringResource(
            R.plurals.hero_projection_files, projection.fileCount, projection.fileCount
        )
        else -> null
    }
}

/** Plain-English reason a file was skipped. */
@Composable
fun skipReasonLabel(reason: String): String = when (reason) {
    "removed_before_upload" -> stringResource(R.string.skip_removed_early)
    "no_uri" -> stringResource(R.string.skip_unreadable)
    "out_of_memory" -> stringResource(R.string.skip_too_large)
    "user_excluded" -> stringResource(R.string.skip_user_excluded)
    "duplicate" -> stringResource(R.string.skip_duplicate)
    "returned_copy" -> stringResource(R.string.skip_returned_copy)
    else -> stringResource(R.string.skip_other)
}

/**
 * One line that always tells the truth: paused, working, waiting for a
 * specific condition (13.G), or all done.
 */
@Composable
private fun statusLine(
    options: Options,
    waiting: Int?,
    heldBack: Int = 0,
    inFolder: Int = 0,
    noAlbumsTicked: Boolean = false,
    processed: Int = 0
): String {
    if (options.pauseAll) return stringResource(R.string.status_paused)
    // Nothing ticked is why the queue is empty, and it is the only thing
    // worth saying while it is true. Without this the queue read as zero and
    // the line below announced that everything was backed up on a phone
    // where not one photo had ever been offered to the app - the worst kind
    // of wrong, because it is reassuring.
    if (noAlbumsTicked) return stringResource(R.string.status_no_albums)
    // CC1.3: files compressed and waiting for a pacing slot are not a
    // failure and must never read as one. Pacing exists so each file can be
    // confirmed on its own; the line says that plainly rather than leaving a
    // queue that looks stuck.
    if (heldBack > 0) {
        return if (inFolder > 0) {
            pluralStringResource(R.plurals.pacing_held, inFolder, inFolder, heldBack)
        } else {
            pluralStringResource(R.plurals.pacing_held_none, heldBack, heldBack)
        }
    }
    // Nothing counted yet - said after the counts that are known, so real
    // news is never suppressed by a figure that has not landed. Saying
    // nothing for a moment is honest; what this replaced said "Everything is
    // backed up" before it had any idea, on every single launch.
    if (waiting == null) return ""
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
    // "Everything is backed up" is a claim about work that happened. A run
    // that found nothing to do has not backed anything up, so an empty queue
    // with an empty history says so plainly instead.
    return if (options.lastRunAt > 0 && processed > 0) {
        stringResource(R.string.status_idle)
    } else {
        stringResource(R.string.status_fresh)
    }
}

/**
 * One half of the saved-space split inside the hero banner.
 *
 * Photos and videos behave nothing alike - a single 4K clip can outweigh a
 * thousand photos - so the two are worth seeing apart. Each carries its own
 * icon so the pair reads at a glance rather than as a sentence.
 */
@Composable
private fun HeroStat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(OnBrand.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = OnBrandMuted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            // Half a hero card is a narrow place for a word. The weight lets
            // the label wrap inside it instead of stretching the row past the
            // half it was given.
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = OnBrandMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
            color = OnBrand,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
