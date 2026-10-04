package app.cloudsaver.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.cloudsaver.R
import app.cloudsaver.core.logic.FolderName
import app.cloudsaver.core.logic.OutFolder
import app.cloudsaver.core.logic.OutputLayout
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.theme.Dimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One line per folder in use - which kind of copy goes there, the exact
 * path, and a Change button - so a person can keep the default or give each
 * kind a folder of their own.
 */
@Composable
fun FolderChoiceRows(layout: OutputLayout, onChange: (OutFolder) -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        for (folder in OutputLayout.folders(layout.mode)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.TouchTarget)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(folderLabel(folder)) + if (layout.isCustom(folder)) {
                            ""
                        } else {
                            " " + stringResource(R.string.folder_default_tag)
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        layout.path(folder),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { onChange(folder) }) {
                    Text(stringResource(R.string.folder_change))
                }
            }
        }
    }
}

private fun folderLabel(folder: OutFolder): Int = when (folder) {
    OutFolder.SINGLE -> R.string.folder_for_all
    OutFolder.PHOTOS -> R.string.folder_for_photos
    OutFolder.VIDEOS -> R.string.folder_for_videos
}

/**
 * Default or a name of the person's own, for one kind of copy.
 *
 * The name is checked as it is typed - the rules first, then whether the
 * folder already holds photos or videos of the person's own, which Ente
 * Saver's copies must never be mixed in with. Saving says the one thing to
 * change in Ente; the old folder is watched until Ente has every copy in it.
 */
@Composable
fun FolderDialog(
    vm: AppViewModel,
    layout: OutputLayout,
    folder: OutFolder,
    onDone: () -> Unit
) {
    val defaultPath = OutputLayout(layout.mode).path(folder)
    var own by rememberSaveable { mutableStateOf(layout.isCustom(folder)) }
    var name by rememberSaveable {
        mutableStateOf(if (layout.isCustom(folder)) FolderName.nameOf(layout.path(folder)) else "")
    }
    // Null while the name typed last has not been checked yet.
    var check by remember { mutableStateOf<AppViewModel.FolderCheck?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(own, name) {
        check = null
        if (!own) return@LaunchedEffect
        // A short pause, so the gallery is not asked on every keystroke.
        delay(250)
        check = vm.checkFolderName(folder, name)
    }
    val canSave = !saving && (!own || check?.ok == true)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(folderLabel(folder))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ChoiceRow(
                    selected = !own,
                    label = stringResource(R.string.folder_use_default, defaultPath),
                    onClick = { own = false }
                )
                ChoiceRow(
                    selected = own,
                    label = stringResource(R.string.folder_use_own),
                    onClick = { own = true }
                )
                if (own) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it.take(FolderName.MAX_LENGTH + 5)
                            check = null
                        },
                        label = { Text(stringResource(R.string.folder_name_label)) },
                        prefix = { Text(FolderName.PREFIX) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        isError = name.isNotBlank() && check?.ok == false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                    val done = check
                    val problem = done?.problem
                    val note = when {
                        done == null || problem == FolderName.Problem.EMPTY -> null
                        problem != null -> stringResource(problemText(problem))
                        done.taken > 0 -> pluralStringResource(R.plurals.folder_problem_taken, done.taken, done.taken)
                        done.unchecked -> stringResource(R.string.folder_problem_unchecked)
                        else -> null
                    }
                    note?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                Text(
                    stringResource(R.string.folder_change_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    saving = true
                    scope.launch {
                        val result = vm.saveFolder(folder, if (own) name else null)
                        saving = false
                        if (result.ok) onDone() else check = result
                    }
                }
            ) { Text(stringResource(R.string.folder_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun ChoiceRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.TouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun problemText(problem: FolderName.Problem): Int = when (problem) {
    FolderName.Problem.EMPTY -> R.string.folder_problem_empty
    FolderName.Problem.TOO_LONG -> R.string.folder_problem_too_long
    FolderName.Problem.BAD_CHARACTER -> R.string.folder_problem_character
    FolderName.Problem.HIDDEN -> R.string.folder_problem_hidden
    FolderName.Problem.RESERVED -> R.string.folder_problem_reserved
    FolderName.Problem.SAME_AS_OTHER -> R.string.folder_problem_same
}
