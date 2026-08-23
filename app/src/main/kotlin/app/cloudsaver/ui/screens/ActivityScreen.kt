package app.cloudsaver.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.R
import app.cloudsaver.data.db.ActivityRow
import app.cloudsaver.engine.ActivityLog
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.Routes
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.EmptyState
import app.cloudsaver.ui.components.SegmentedChoice
import app.cloudsaver.util.Formats

/**
 * What the app has actually been doing.
 *
 * Background work is invisible by nature and a notification is gone the moment
 * it is swiped, so "is this thing even running" has no answer anywhere else.
 * Every line here is also the reason a notification was posted, which means
 * the user can turn notifications off without losing anything.
 */
@Composable
fun ActivityScreen(vm: AppViewModel, nav: NavHostController) {
    val rows by vm.activityRows.collectAsStateWithLifecycle()
    val filter by vm.activityFilter.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    // Opening the screen is what marks it read; the dot on Home goes with it.
    LaunchedEffect(Unit) { vm.markActivitySeen() }

    val exportOk = stringResource(R.string.activity_export_ok)
    val exportFail = stringResource(R.string.activity_export_failed)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> uri?.let { vm.exportActivity(it, exportOk, exportFail) } }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
            Text(
                stringResource(R.string.nav_activity),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        SegmentedChoice(
            options = listOf(
                "ALL" to stringResource(R.string.activity_filter_all),
                ActivityLog.Group.BACKUPS.name to stringResource(R.string.activity_filter_backups),
                ActivityLog.Group.PROBLEMS.name to stringResource(R.string.activity_filter_problems),
                ActivityLog.Group.CHANGES.name to stringResource(R.string.activity_filter_changes)
            ),
            selected = filter?.name ?: "ALL",
            onSelect = { value ->
                vm.activityFilter.value = if (value == "ALL") {
                    null
                } else {
                    ActivityLog.Group.valueOf(value)
                }
            }
        )

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = { exportLauncher.launch("cloudsaver-activity.txt") },
                enabled = rows.isNotEmpty()
            ) { Text(stringResource(R.string.activity_export)) }
            TextButton(
                onClick = { confirmClear = true },
                enabled = rows.isNotEmpty()
            ) { Text(stringResource(R.string.activity_clear)) }
        }

        if (rows.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.activity_empty_title),
                body = stringResource(R.string.activity_empty_body)
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                var lastDay = ""
                for (row in rows) {
                    val day = Formats.date(row.atMs)
                    if (day != lastDay) {
                        lastDay = day
                        item(key = "day-$day") {
                            Text(
                                day,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                            )
                        }
                    }
                    item(key = row.id) { ActivityCard(row, nav, vm) }
                }
                item(key = "footer") {
                    Text(
                        stringResource(R.string.activity_retention),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.activity_clear_title)) },
            text = { Text(stringResource(R.string.activity_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearActivity()
                    confirmClear = false
                }) { Text(stringResource(R.string.activity_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ActivityCard(row: ActivityRow, nav: NavHostController, vm: AppViewModel) {
    val kind = runCatching { ActivityLog.Kind.valueOf(row.kind) }.getOrNull()
    // A row that names a group of files takes you to exactly those files,
    // rather than to a list you then have to search yourself.
    val target = row.filterState
    AppCard(
        modifier = Modifier.padding(vertical = 4.dp),
        onClick = if (target != null) {
            {
                vm.filesState.value = target
                nav.navigate(Routes.FILES)
            }
        } else {
            null
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    kind?.let { headline(it, row) } ?: row.kind,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                row.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Text(
                Formats.time(row.atMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** One line, in the words the rest of the app uses. */
@Composable
private fun headline(kind: ActivityLog.Kind, row: ActivityRow): String = when (kind) {
    ActivityLog.Kind.OPTIMISED ->
        stringResource(R.string.activity_optimised, Formats.count(row.count), Formats.bytes(row.bytes))
    ActivityLog.Kind.RELEASED ->
        stringResource(R.string.activity_released, Formats.count(row.count))
    ActivityLog.Kind.BACKED_UP ->
        stringResource(R.string.activity_backed_up, Formats.count(row.count))
    ActivityLog.Kind.RECLAIMED ->
        stringResource(R.string.activity_reclaimed, Formats.count(row.count), Formats.bytes(row.bytes))
    ActivityLog.Kind.PAUSED -> stringResource(R.string.activity_paused)
    ActivityLog.Kind.RESUMED -> stringResource(R.string.activity_resumed)
    ActivityLog.Kind.CLOUD_PROBLEM -> stringResource(R.string.activity_cloud_problem)
    ActivityLog.Kind.SKIPPED -> stringResource(R.string.activity_skipped)
    ActivityLog.Kind.SETTINGS_CHANGED -> stringResource(R.string.activity_settings)
    ActivityLog.Kind.RECOVERED -> stringResource(R.string.activity_recovered)
}
