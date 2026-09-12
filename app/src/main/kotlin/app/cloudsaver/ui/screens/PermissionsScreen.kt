package app.cloudsaver.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavHostController
import app.cloudsaver.R
import app.cloudsaver.engine.UsageVerifier
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.SectionHeader
import app.cloudsaver.ui.theme.Dimens
import app.cloudsaver.util.OemPages
import app.cloudsaver.util.Permissions
import app.cloudsaver.util.PowerPages

/**
 * Every permission and battery switch this app depends on, with its live
 * state and the way to it - the permission manager a sideloaded app has to
 * be for itself.
 *
 * Setup showed the battery rows once and Settings showed none of them, so a
 * Realme owner whose phone stopped the app a day later had no way back to
 * the auto-launch page short of finding it in the system by hand. Each row
 * here re-reads its state every time the screen comes to the front, says
 * plainly which states Android can report and which it cannot, and for the
 * ones it cannot, names the page in the phone's own words.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PermissionsScreen(vm: AppViewModel, nav: NavHostController) {
    val context = LocalContext.current
    // Bumped on every return to the screen: each state below is read fresh
    // through it, because all of them are granted on some other screen.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        tick++
        vm.refreshPowerRequirements()
    }
    val access = remember(tick) { Permissions.mediaAccess(context) }
    val notifications = remember(tick) { Permissions.hasNotifications(context) }
    val usage = remember(tick) { UsageVerifier.hasUsageAccess(context) }
    val battery = remember(tick) { Permissions.isIgnoringBatteryOptimizations(context) }
    val vendor = remember { PowerPages.vendor() }
    val makerRows = remember(vendor, battery) {
        PowerPages.requirementsFor(vendor, battery).filter { !it.readable }
    }

    // Asking is one tap; a refusal Android has stopped asking about goes to
    // the page where the switch lives instead of a button that does nothing.
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        tick++
        if (Permissions.mediaAccess(context) != Permissions.MediaAccess.FULL) {
            OemPages.openAppInfo(context)
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        tick++
        if (!ok) OemPages.openNotificationSettings(context)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.padding(top = 8.dp, start = 4.dp, end = Dimens.Screen),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
            Text(
                stringResource(R.string.perm_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }
        Column(Modifier.padding(horizontal = Dimens.Screen)) {
            Text(
                stringResource(R.string.perm_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionHeader(stringResource(R.string.perm_group_access))
            PermissionRow(
                title = stringResource(R.string.perm_media),
                status = stringResource(
                    when (access) {
                        Permissions.MediaAccess.FULL -> R.string.perm_media_full
                        Permissions.MediaAccess.PARTIAL -> R.string.perm_media_partial
                        Permissions.MediaAccess.NONE -> R.string.perm_media_none
                    }
                ),
                state = if (access == Permissions.MediaAccess.FULL) State.OK else State.PROBLEM,
                actionLabel = stringResource(R.string.perm_change)
            ) {
                if (access == Permissions.MediaAccess.FULL) {
                    OemPages.openAppInfo(context)
                } else {
                    mediaLauncher.launch(Permissions.mediaPermissionsToRequest())
                }
            }
            PermissionRow(
                title = stringResource(R.string.perm_notifications),
                status = stringResource(
                    if (notifications) R.string.perm_on else R.string.perm_notifications_off
                ),
                state = if (notifications) State.OK else State.PROBLEM,
                actionLabel = stringResource(
                    if (notifications) R.string.perm_open else R.string.perm_allow
                )
            ) {
                if (!notifications && Build.VERSION.SDK_INT >= 33) {
                    notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    OemPages.openNotificationSettings(context)
                }
            }
            PermissionRow(
                title = stringResource(R.string.perm_usage),
                status = stringResource(if (usage) R.string.perm_usage_on else R.string.perm_usage_off),
                state = if (usage) State.OK else State.PROBLEM,
                actionLabel = stringResource(R.string.perm_open)
            ) { OemPages.openUsageAccess(context) }

            SectionHeader(stringResource(R.string.perm_group_battery))
            PermissionRow(
                title = stringResource(R.string.perm_battery),
                status = stringResource(
                    if (battery) R.string.perm_battery_on else R.string.perm_battery_off
                ),
                state = if (battery) State.OK else State.PROBLEM,
                detail = PowerPages.pathHint(vendor, PowerPages.ID_BATTERY_UNRESTRICTED),
                actionLabel = stringResource(
                    if (battery) R.string.perm_open else R.string.perm_allow
                )
            ) { PowerPages.open(context, PowerPages.ID_BATTERY_UNRESTRICTED) }
            for (requirement in makerRows) {
                PermissionRow(
                    title = stringResource(
                        if (requirement.id == PowerPages.ID_AUTO_LAUNCH) R.string.perm_auto_launch
                        else R.string.perm_background
                    ),
                    status = stringResource(R.string.perm_unknown),
                    state = State.UNKNOWN,
                    detail = PowerPages.pathHint(vendor, requirement.id),
                    actionLabel = stringResource(R.string.perm_open)
                ) { PowerPages.open(context, requirement.id) }
            }

            Text(
                stringResource(R.string.perm_why),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
            TextButton(onClick = { OemPages.openAppInfo(context) }) {
                Text(stringResource(R.string.perm_app_info))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private enum class State { OK, PROBLEM, UNKNOWN }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionRow(
    title: String,
    status: String,
    state: State,
    actionLabel: String,
    detail: String? = null,
    onAction: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    AppCard(modifier = Modifier.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                when (state) {
                    State.OK -> Icons.Outlined.CheckCircle
                    State.PROBLEM -> Icons.Outlined.ErrorOutline
                    State.UNKNOWN -> Icons.Outlined.Info
                },
                contentDescription = null,
                tint = when (state) {
                    State.OK -> scheme.primary
                    State.PROBLEM -> scheme.error
                    State.UNKNOWN -> scheme.onSurfaceVariant
                },
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state) {
                        State.OK -> scheme.primary
                        State.PROBLEM -> scheme.error
                        State.UNKNOWN -> scheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        // Its own line, flowing: beside the text at the largest font the
        // button squeezed the words into a column one word wide.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        ) {
            OutlinedButton(onClick = onAction) {
                Text(actionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
