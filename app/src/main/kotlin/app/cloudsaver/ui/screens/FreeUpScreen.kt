package app.cloudsaver.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.BadgeTone
import app.cloudsaver.ui.components.EmptyState
import app.cloudsaver.ui.components.StateBadge
import app.cloudsaver.util.Formats

/**
 * Free-up originals: the ONLY way originals are ever deleted - manual selection
 * plus Android's own system confirmation dialog (MediaStore.createDeleteRequest).
 */
@Composable
fun FreeUpScreen(vm: AppViewModel, nav: NavHostController) {
    val items by vm.freeUpItems.collectAsStateWithLifecycle()
    val tampered by vm.tampered.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val context = androidx.compose.ui.platform.LocalContext.current

    app.cloudsaver.ui.components.SecureScreen()
    LaunchedEffect(Unit) { vm.loadFreeUp() }

    if (tampered) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Spacer(Modifier.height(24.dp))
            AppCard {
                Text(
                    stringResource(R.string.tamper_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    stringResource(R.string.tamper_freeup_blocked),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        return
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        vm.onDeleteDialogResult(result.resultCode == android.app.Activity.RESULT_OK)
    }
    val legacyIntent by vm.legacyDeleteIntent.collectAsStateWithLifecycle()
    LaunchedEffect(legacyIntent) {
        legacyIntent?.let {
            deleteLauncher.launch(IntentSenderRequest.Builder(it).build())
        }
    }

    val selectedRows = items.filter { it.id in selected }
    val totalBytes = selectedRows.sumOf { it.sizeBytes }
    val allBytes = items.sumOf { it.sizeBytes }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.freeup_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            stringResource(R.string.freeup_subtitle, Formats.bytes(allBytes)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.freeup_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        if (items.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.freeup_empty_title),
                body = stringResource(R.string.freeup_empty)
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                selected = if (selected.size == items.size) {
                    emptySet()
                } else {
                    items.map { it.id }.toSet()
                }
            }) {
                Text(
                    if (selected.size == items.size) {
                        stringResource(R.string.freeup_select_none)
                    } else {
                        stringResource(R.string.freeup_select_all)
                    }
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.freeup_selected_count, selectedRows.size, items.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.id }) { row ->
                val checked = row.id in selected
                AppCard(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .animateItem(),
                    tonal = checked,
                    onClick = {
                        selected = if (checked) selected - row.id else selected + row.id
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                selected = if (on) selected + row.id else selected - row.id
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1
                            )
                            Row(
                                Modifier.padding(top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StateBadge(evidenceLabel(row), BadgeTone.SUCCESS)
                                Text(
                                    Formats.bytes(row.sizeBytes),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // The destructive action sits apart from the list, always reachable.
        Button(
            enabled = selectedRows.isNotEmpty(),
            onClick = {
                val uris = vm.urisFor(selectedRows)
                if (uris.isNotEmpty()) {
                    val sender = vm.requestDelete(uris) { deleted ->
                        vm.onFreedByUris(deleted)
                    }
                    sender?.let {
                        deleteLauncher.launch(IntentSenderRequest.Builder(it).build())
                    }
                    selected = emptySet()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) { Text(stringResource(R.string.freeup_delete, Formats.bytes(totalBytes))) }
    }
}
