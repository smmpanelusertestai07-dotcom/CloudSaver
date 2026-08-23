package app.litesaver.ui.screens

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
import app.litesaver.R
import app.litesaver.ui.AppViewModel
import app.litesaver.ui.components.GlassCard
import app.litesaver.util.Formats

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

    app.litesaver.ui.components.SecureScreen()
    LaunchedEffect(Unit) { vm.loadFreeUp() }

    if (tampered) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Spacer(Modifier.height(24.dp))
            GlassCard {
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
            modifier = Modifier.padding(vertical = 6.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                selected = if (selected.size == items.size) emptySet() else items.map { it.id }.toSet()
            }) { Text(stringResource(R.string.freeup_select_all)) }
            Spacer(Modifier.weight(1f))
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
                }
            ) { Text(stringResource(R.string.freeup_delete, Formats.bytes(totalBytes))) }
        }
        if (items.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            GlassCard {
                Text(
                    stringResource(R.string.freeup_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { row ->
                GlassCard(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = row.id in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + row.id else selected - row.id
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1
                            )
                            Text(
                                "${Formats.bytes(row.sizeBytes)} - ${evidenceLabel(row)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
