package com.pocketide.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.github.RepoInfo
import com.pocketide.projects.RepoAddress
import com.pocketide.projects.RepoNotReachableException
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.screens.project.WorkFormat
import com.pocketide.ui.screens.project.Trust
import com.pocketide.ui.screens.project.attempt
import com.pocketide.ui.screens.project.finish
import com.pocketide.ui.screens.project.plainReason
import com.pocketide.ui.screens.project.rememberGraph
import com.pocketide.ui.screens.project.trustOf
import kotlinx.coroutines.launch

/** "New project": a new private repository on the owner's GitHub. */
@Composable
internal fun NewProjectDialog(onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val nameProblem = if (name.isEmpty()) null else repoNameProblem(name)

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "PocketIDE creates a private repository on your GitHub for it. Only you can see it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    singleLine = true,
                    label = { Text("Name") },
                    isError = nameProblem != null,
                    supportingText = nameProblem?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(300) },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            Button(
                enabled = !creating && repoNameProblem(name) == null,
                onClick = {
                    creating = true
                    problem = null
                    scope.launch {
                        finish { graph.projects.create(name.trim(), description.trim()) }
                            .onSuccess { onCreated(it.id) }
                            .onFailure { problem = "Could not create it: ${plainReason(it)}" }
                        creating = false
                    }
                },
            ) {
                if (creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Create")
            }
        },
        dismissButton = { TextButton(enabled = !creating, onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * "Import": the repositories PocketIDE's GitHub App can reach, searchable. The owner chooses
 * which repositories the app sees on GitHub ("only selected repositories").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportSheet(
    addedIds: Set<String>,
    onDismiss: () -> Unit,
    onImported: (String) -> Unit,
    onAddRepositories: (String) -> Unit,
) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var repos by remember { mutableStateOf<Result<List<RepoInfo>>?>(null) }
    var query by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    // Where to add a repository PocketIDE cannot reach yet; the one from the refusal wins.
    var addUrl by remember { mutableStateOf<String?>(null) }
    // The repository to import again once the owner is back from adding it on GitHub.
    var refused by remember { mutableStateOf<RepoAddress?>(null) }
    var awaitingReturn by remember { mutableStateOf<RepoAddress?>(null) }
    val installUrl = remember { runCatching { graph.gitHubAuth.installUrl() }.getOrNull() }
    val account by graph.gitHubAuth.account.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { repos = attempt { graph.gitHub.repos() } }

    fun import(id: String, owner: String, repo: String, label: String) {
        importing = id
        problem = null
        addUrl = null
        refused = null
        scope.launch {
            finish { graph.projects.import(owner, repo) }
                .onSuccess { onImported(it.id) }
                .onFailure { e ->
                    problem = "Could not import $label: ${plainReason(e)}"
                    if (e is RepoNotReachableException) {
                        addUrl = e.installUrl
                        refused = e.address
                    }
                }
            importing = null
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val address = awaitingReturn ?: return@LifecycleEventEffect
        awaitingReturn = null
        val label = "${address.owner}/${address.repo}"
        import(label, address.owner, address.repo, label)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Import a repository", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search, or paste a GitHub address") },
                modifier = Modifier.fillMaxWidth(),
            )
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            addUrl?.let { url ->
                TextButton(onClick = {
                    awaitingReturn = refused
                    onAddRepositories(url)
                }) { Text(if (refused != null) "Add it to PocketIDE on GitHub, then come back" else "Add it to PocketIDE on GitHub") }
            }
            pastedRepo(query, repos?.getOrNull().orEmpty())?.let { address ->
                val label = "${address.owner}/${address.repo}"
                ListItem(
                    headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("The address you pasted") },
                    trailingContent = {
                        if (importing == label) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(enabled = importing == null, onClick = { import(label, query, "", label) }) { Text("Import") }
                        }
                    },
                )
            }
            val result = repos
            when {
                result == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                result.isFailure -> Text(
                    "Could not list your repositories: ${plainReason(result.exceptionOrNull() ?: IllegalStateException())}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> {
                    val shown = filterRepos(result.getOrThrow(), query, addedIds)
                    if (shown.isEmpty()) {
                        Text(
                            if (query.isBlank()) "PocketIDE cannot see any repositories yet." else "No repository matches \"$query\".",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        items(shown, key = { repoId(it) }) { repo ->
                            val added = repoId(repo) in addedIds
                            ListItem(
                                headlineContent = { Text(repo.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    val whose = trustOf(repo.owner, account?.login)
                                    Text(
                                        "${repo.owner} · ${WorkFormat.bytes(repo.sizeKb * 1024)}" +
                                            if (whose == Trust.SOMEONE_ELSES) " · ${whose.label}" else "",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        StatusChip(if (repo.isPrivate) "Private" else "Public", if (repo.isPrivate) Tone.OK else Tone.WARN)
                                        Spacer(Modifier.width(8.dp))
                                        when {
                                            added -> Text("Added", style = MaterialTheme.typography.labelMedium)
                                            importing == repoId(repo) -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                            else -> TextButton(
                                                enabled = importing == null,
                                                onClick = { import(repoId(repo), repo.owner, repo.name, repo.name) },
                                            ) { Text("Import") }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (installUrl != null && addUrl == null) {
                TextButton(onClick = { onAddRepositories(installUrl) }) { Text("Choose which repositories PocketIDE can see") }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}
