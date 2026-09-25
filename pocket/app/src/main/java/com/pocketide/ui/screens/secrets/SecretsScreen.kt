package com.pocketide.ui.screens.secrets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.core.Ist
import com.pocketide.secrets.ProjectValue
import com.pocketide.secrets.SecretKind
import com.pocketide.ui.components.DialogBody
import com.pocketide.ui.components.KeepTypedInput
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.SelectableText
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.manage.ActionRunner
import com.pocketide.ui.manage.ConfirmDialog
import com.pocketide.ui.manage.EmptyNote
import com.pocketide.ui.manage.Hint
import com.pocketide.ui.manage.ManagePage
import com.pocketide.ui.manage.NavRow
import com.pocketide.ui.manage.PlainError
import com.pocketide.ui.manage.SectionLabel
import com.pocketide.ui.manage.ValueNames
import com.pocketide.ui.manage.findFragmentActivity
import com.pocketide.ui.manage.rememberActionRunner
import com.pocketide.ui.manage.rememberGraph
import com.pocketide.ui.nav.PocketNav

private const val MASK = "••••••••"

/** What the editor dialog is doing: adding a new value, or replacing an existing one. */
private data class Editing(val existing: ProjectValue?)

/** A value shown after the fingerprint; wiped when the dialog closes. */
private class Revealed(val name: String, val chars: CharArray)

/**
 * Variables (the agent sees them) and Secrets (never the agent: only set-up steps and GitHub
 * Actions builds), for one project or the global set. Values stay masked; showing one needs
 * the fingerprint or screen lock.
 */
@Composable
fun SecretsScreen(projectId: String?, nav: PocketNav) {
    val graph = rememberGraph()
    val runner = rememberActionRunner()
    val context = LocalContext.current
    val all by graph.secrets.values.collectAsStateWithLifecycle()
    val projects by graph.projects.all.collectAsStateWithLifecycle()
    val project = projects.firstOrNull { it.id == projectId }
    val values = remember(all, projectId) { ValueNames.scoped(all, projectId) }
    val globals = remember(all) { ValueNames.scoped(all, null) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var deleting by remember { mutableStateOf<ProjectValue?>(null) }
    var pushing by remember { mutableStateOf<ProjectValue?>(null) }
    var revealed by remember { mutableStateOf<Revealed?>(null) }

    fun reveal(value: ProjectValue) {
        val activity = context.findFragmentActivity()
        if (activity == null) {
            runner.say("Could not ask for your fingerprint here.")
            return
        }
        val onPassed = { passed: Boolean ->
            if (passed) {
                runner.run(
                    key = "reveal:${value.name}",
                    onSuccess = { chars: CharArray? ->
                        if (chars == null) runner.say("This value could not be read on this phone.") else revealed = Revealed(value.name, chars)
                    },
                ) { graph.secrets.reveal(value.projectId, value.name) }
            }
        }
        try {
            graph.appLock.authenticate(activity, "Show ${value.name}", onPassed)
        } catch (e: Exception) {
            runner.say(PlainError.of(e))
        }
    }

    val title = if (projectId == null) "Global Variables and Secrets" else "Variables and Secrets"
    ManagePage(title, nav, runner) {
        item { Explainer(projectLabel = project?.let { "${it.owner}/${it.repo}" } ?: projectId) }
        item {
            Button(onClick = { editing = Editing(null) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add a Variable or Secret")
            }
        }
        if (values.isEmpty()) {
            item {
                EmptyNote(
                    Icons.Outlined.Key,
                    "Nothing saved yet",
                    "When an agent needs a key it asks you to add it here, never in the chat.",
                )
            }
        }
        for (kind in SecretKind.entries) {
            val ofKind = values.filter { it.kind == kind }
            if (ofKind.isEmpty()) continue
            item(key = "label:$kind") { SectionLabel(if (kind == SecretKind.VARIABLE) "Variables" else "Secrets") }
            items(ofKind, key = { "${it.kind}:${it.name}" }) { value ->
                ValueRow(
                    value = value,
                    canPush = projectId != null && value.kind == SecretKind.SECRET,
                    runner = runner,
                    onReveal = { reveal(value) },
                    onEdit = { editing = Editing(value) },
                    onPush = { pushing = value },
                    onDelete = { deleting = value },
                )
            }
        }
        if (projectId != null) {
            item { SectionLabel("Also for every project") }
            item {
                SectionCard(null) {
                    Hint(
                        if (globals.isEmpty()) {
                            "No global values. They apply to all projects; a project's own value with the same name wins."
                        } else {
                            "${globals.size} global ${if (globals.size == 1) "value applies" else "values apply"} here too; " +
                                "a project's own value with the same name wins."
                        },
                    )
                    NavRow(Icons.Outlined.Public, "Global Variables and Secrets", null) { nav.secrets(null) }
                }
            }
        }
    }

    editing?.let { edit ->
        ValueEditor(
            existing = edit.existing,
            taken = values.map { it.name },
            onDismiss = { editing = null },
            onSave = { name, kind, value ->
                editing = null
                if (runner.isBusy("save:$name")) {
                    value.fill('\u0000')
                    runner.say("$name is still being saved. Try again in a moment.")
                    return@ValueEditor
                }
                runner.run("save:$name", done = "$name saved.") {
                    try {
                        graph.secrets.set(projectId, name, kind, value)
                    } finally {
                        value.fill('\u0000')
                    }
                }
            },
        )
    }
    deleting?.let { value ->
        ConfirmDialog(
            title = "Delete ${value.name}?",
            text = if (value.pushedToGitHub) {
                "It is removed from PocketIDE and your Drive. The copy in GitHub Actions stays until you delete it in the repository's settings."
            } else {
                "It is removed from this phone and your Drive."
            },
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { runner.run("delete:${value.name}", done = "${value.name} deleted.") { graph.secrets.remove(value.projectId, value.name) } },
            onDismiss = { deleting = null },
        )
    }
    pushing?.let { value ->
        ConfirmDialog(
            title = "Send ${value.name} to GitHub?",
            text = "It becomes a GitHub Actions secret of ${project?.repo ?: "this project"}. GitHub encrypts it; your builds can use it " +
                "and agents never see it.",
            confirmLabel = "Send",
            destructive = false,
            onConfirm = {
                val id = projectId ?: return@ConfirmDialog
                runner.run("push:${value.name}", done = "${value.name} is now a GitHub Actions secret.") { graph.secrets.pushToGitHub(id, value.name) }
            },
            onDismiss = { pushing = null },
        )
    }
    // A shown value is hidden as soon as the app leaves the screen (recent apps, another app).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { revealed = null }
    revealed?.let { shown ->
        DisposableEffect(shown) { onDispose { shown.chars.fill('\u0000') } }
        AlertDialog(
            onDismissRequest = { revealed = null },
            title = { Text(shown.name) },
            text = { SelectableText(String(shown.chars), Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { revealed = null }) { Text("Hide") } },
        )
    }
}

@Composable
private fun Explainer(projectLabel: String?) {
    SectionCard(null) {
        Text(
            if (projectLabel != null) "For $projectLabel" else "For every project",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(lead("Variables", "the agent sees: test keys, API addresses, feature flags."), style = MaterialTheme.typography.bodyMedium)
        Text(lead("Secrets", "never the agent: only set-up steps and your GitHub Actions builds."), style = MaterialTheme.typography.bodyMedium)
        Hint("Both are encrypted on this phone and in your Drive, and never go into git.")
    }
}

private fun lead(term: String, rest: String) = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(term) }
    append(" ")
    append(rest)
}

@Composable
private fun ValueRow(
    value: ProjectValue,
    canPush: Boolean,
    runner: ActionRunner,
    onReveal: () -> Unit,
    onEdit: () -> Unit,
    onPush: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(value.name, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                Text(MASK, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Hint("Changed ${Ist.dateTime(value.updatedAt)}")
            }
            if (value.pushedToGitHub) StatusChip("In GitHub", Tone.OK)
        }
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onReveal, enabled = !runner.isBusy("reveal:${value.name}")) {
                Icon(Icons.Outlined.Visibility, contentDescription = "Show ${value.name}")
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Change ${value.name}") }
            if (canPush) {
                IconButton(onClick = onPush, enabled = !runner.isBusy("push:${value.name}")) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = "Send ${value.name} to GitHub Actions")
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${value.name}") }
        }
    }
}

@Composable
private fun ValueEditor(
    existing: ProjectValue?,
    taken: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, SecretKind, CharArray) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.kind ?: SecretKind.VARIABLE) }
    var value by remember { mutableStateOf("") }
    var showValue by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    val nameProblem = ValueNames.problem(name, kind)
        ?: if (existing == null && taken.any { ValueNames.sameName(it, name) }) "A value with this name already exists." else null
    val valueProblem = if (value.isEmpty()) "Enter the value." else null

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = KeepTypedInput,
        title = { Text(if (existing == null) "Add a value" else "Change ${existing.name}") },
        text = {
            DialogBody {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text("Name") },
                    enabled = existing == null,
                    singleLine = true,
                    isError = tried && nameProblem != null,
                    supportingText = { Text(if (tried && nameProblem != null) nameProblem else "For example API_BASE_URL") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                KindChoice(SecretKind.VARIABLE, kind, "Variable", "The agent sees it in its room.") { kind = it }
                KindChoice(SecretKind.SECRET, kind, "Secret", "Never the agent. Set-up steps and GitHub builds only.") { kind = it }
                if (existing?.kind == SecretKind.SECRET && kind == SecretKind.VARIABLE) {
                    Text(
                        "As a Variable, the agent will see this value in its room.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(if (existing == null) "Value" else "New value") },
                    singleLine = true,
                    isError = tried && valueProblem != null,
                    supportingText = if (tried && valueProblem != null) ({ Text(valueProblem) }) else null,
                    visualTransformation = if (showValue) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { showValue = !showValue }) {
                            Icon(
                                if (showValue) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showValue) "Hide value" else "Show value",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tried = true
                if (nameProblem == null && valueProblem == null) {
                    onSave(name.trim(), kind, value.toCharArray())
                    value = ""
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun KindChoice(option: SecretKind, selected: SecretKind, title: String, detail: String, onSelect: (SecretKind) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = option == selected, role = Role.RadioButton) { onSelect(option) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = option == selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(detail)
        }
    }
}
