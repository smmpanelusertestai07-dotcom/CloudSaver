package com.pocketide.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pocketide.cloud.ComputerConfig
import com.pocketide.cloud.OpenStep
import com.pocketide.cloud.SetUpFiles
import com.pocketide.cloud.SetUpNeededException
import com.pocketide.github.RepoInfo
import com.pocketide.graph
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.screens.computer.OpeningSteps
import com.pocketide.ui.shell.FinePrint
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.ScreenTitle
import com.pocketide.ui.shell.ShellPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A new private repository with PocketIDE's set-up, and its cloud computer. */
@Composable
fun NewProjectScreen(onBack: () -> Unit, onReady: () -> Unit) {
    val graph = LocalContext.current.graph
    val here = rememberStillShown()
    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var step by remember { mutableStateOf<OpenStep?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val valid = PROJECT_NAME.matches(name)

    val current = step
    if (current != null) {
        Opening("Making ${name.trim()}", current, addsSetUp = true, problem = problem, onBack = onBack)
        return
    }
    ShellPage {
        BackRow(onBack)
        ScreenTitle(Icons.Outlined.CreateNewFolder, "New project", "A private repository on your GitHub, with its own cloud computer and the three agents.")
        Gap(24.dp)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.replace(' ', '-') },
            label = { Text("Name") },
            singleLine = true,
            isError = name.isNotEmpty() && !valid,
            supportingText = { Text("Letters, numbers, hyphens, dots or underscores.") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = about,
            onValueChange = { about = it.take(DESCRIPTION_MAX) },
            label = { Text("What it is (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Gap(16.dp)
        NoticeCard("New projects are private: only you can see the code, the computer and the chats.", Tone.OK)
        Gap(20.dp)
        PrimaryAction(
            "Make project",
            enabled = valid,
            onClick = {
                problem = null
                step = OpenStep.CHECKING
                // The app's own scope: leaving the screen must not stop the set-up half-way.
                graph.scope.launch(Dispatchers.Main) {
                    try {
                        val choices = graph.settings.settings.value.newComputer
                        val computer = graph.computers.newProject(name.trim(), about.trim(), choices) { step = it }
                        graph.settings.update { it.copy(lastComputer = computer.name) }
                        if (here.value) onReady()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        problem = e.message ?: "The project was not made. Try again."
                    }
                }
            },
        )
        FinePrint("GitHub makes the computer with your choices from Settings. The first start takes a minute or two.")
    }
}

/** The owner's repositories; opening one reuses its computer or makes one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenRepoScreen(onBack: () -> Unit, onReady: () -> Unit) {
    val graph = LocalContext.current.graph
    val here = rememberStillShown()
    var filter by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<Pair<RepoInfo, SetUpFiles>?>(null) }
    var opening by remember { mutableStateOf<RepoInfo?>(null) }
    var step by remember { mutableStateOf(OpenStep.CHECKING) }
    var addsSetUp by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val repos by produceState<Result<List<RepoInfo>>?>(null) {
        value = try {
            Result.success(graph.gitHub.repos())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun open(repo: RepoInfo, addSetUp: Boolean) {
        opening = repo
        addsSetUp = addSetUp
        problem = null
        graph.scope.launch(Dispatchers.Main) {
            try {
                val computer = graph.computers.openFor(repo, graph.settings.settings.value.newComputer, addSetUp) { step = it }
                graph.settings.update { it.copy(lastComputer = computer.name) }
                if (here.value) onReady()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: SetUpNeededException) {
                opening = null
                asking = repo to e.files
            } catch (e: Exception) {
                problem = e.message ?: "The computer did not open. Try again."
            }
        }
    }

    val target = opening
    if (target != null) {
        Opening("Opening ${target.name}", step, addsSetUp = addsSetUp, problem = problem, onBack = onBack)
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open a repository") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        RepoPicker(repos, filter, onFilter = { filter = it }, onOpen = { open(it, addSetUp = false) }, modifier = Modifier.padding(padding))
    }
    asking?.let { (repo, files) ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(if (files == SetUpFiles.OUTDATED) "Update PocketIDE's set-up?" else "Add PocketIDE's set-up?") },
            text = {
                Text(
                    "PocketIDE adds three small files in ${ComputerConfig.FOLDER}/ to ${repo.name}, in one commit on " +
                        "${repo.defaultBranch}. GitHub reads them to make the cloud computer: the three agents, phone-friendly " +
                        "VS Code settings, and a guard against force pushes. Your own files are not changed.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    open(repo, addSetUp = true)
                }) { Text("Add and open") }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RepoPicker(
    repos: Result<List<RepoInfo>>?,
    filter: String,
    onFilter: (String) -> Unit,
    onOpen: (RepoInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filter,
            onValueChange = onFilter,
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        when {
            repos == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            repos.isFailure -> NoticeCard(repos.exceptionOrNull()?.message ?: "GitHub did not answer.", Tone.ERROR, Modifier.padding(16.dp))
            else -> {
                val shown = repos.getOrThrow().filter { filter.isBlank() || it.fullName.contains(filter.trim(), ignoreCase = true) }
                if (shown.isEmpty()) {
                    NoticeCard(
                        "No repositories here. PocketIDE sees only the repositories you chose on GitHub; you can add more in Settings.",
                        Tone.NEUTRAL,
                        Modifier.padding(16.dp),
                    )
                }
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(shown, key = { it.id }) { repo ->
                        RepoRow(repo, onClick = { onOpen(repo) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoRow(repo: RepoInfo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(repo.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(repo.description ?: repo.owner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusChip(if (repo.isPrivate) "Private" else "Public", if (repo.isPrivate) Tone.OK else Tone.NEUTRAL)
    }
}

@Composable
private fun Opening(title: String, step: OpenStep, addsSetUp: Boolean, problem: String?, onBack: () -> Unit) {
    ShellPage {
        BackRow(onBack)
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Gap(24.dp)
        OpeningSteps(step, addsSetUp)
        problem?.let {
            Gap(20.dp)
            NoticeCard(it, Tone.ERROR)
        }
        Gap(16.dp)
        FinePrint("You can leave this screen: GitHub keeps going, and the computer shows on Home.")
    }
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
    }
}

/** False once the screen has gone, so work that outlives it does not navigate on its own. */
@Composable
private fun rememberStillShown(): State<Boolean> {
    val shown = remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { shown.value = false } }
    return shown
}

/** GitHub's rule for repository names, which PocketIDE checks before asking. */
private val PROJECT_NAME = Regex("[A-Za-z0-9._-]{1,100}")
private const val DESCRIPTION_MAX = 350
