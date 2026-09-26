package com.pocketide.ui.screens.onboarding

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketide.AppGraph
import com.pocketide.docs.DocsContent
import com.pocketide.github.DeviceCode
import com.pocketide.github.DevicePoll
import com.pocketide.github.GitHubAppSave
import com.pocketide.github.gitHubAppChoice
import com.pocketide.ui.components.Tone
import com.pocketide.ui.shell.FinePrint
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.QuietAction
import com.pocketide.ui.shell.ScreenTitle
import com.pocketide.ui.shell.SecondaryAction
import com.pocketide.ui.shell.ShellPage
import com.pocketide.ui.web.Browser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * GitHub's device sign-in: the app shows a short code, GitHub's own page opens in Chrome, and the
 * owner approves there. The password never passes through PocketIDE, and Chrome's saved GitHub
 * sign-in (with passkeys and two-factor) just works.
 */
@Composable
internal fun SignInScreen(graph: AppGraph, onRead: (String) -> Unit) {
    val first = remember { !graph.gitHubAuth.configured }
    var editingApp by remember { mutableStateOf(first) }
    var attempt by remember { mutableIntStateOf(0) }
    if (editingApp) {
        GitHubAppForm(
            graph,
            first = first,
            onSaved = {
                editingApp = !graph.gitHubAuth.configured
                attempt++
            },
            onBack = if (graph.gitHubAuth.configured) ({ editingApp = false }) else null,
            onRead = onRead,
        )
        return
    }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<DeviceCode?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        problem = null
        code = null
        val started = try {
            graph.gitHubAuth.startDeviceFlow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            problem = e.message ?: SIGN_IN_FAILED
            return@LaunchedEffect
        }
        code = started
        problem = waitForApproval(graph, started)
    }

    ShellPage {
        ScreenTitle(Icons.Outlined.Key, "Sign in with GitHub", "GitHub's own page opens in Chrome. Enter this code there and approve PocketIDE.")
        Gap(28.dp)
        val shown = code
        if (shown != null) {
            Text(
                shown.userCode,
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
            )
            Gap(24.dp)
            PrimaryAction(
                "Copy code and open GitHub",
                onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("GitHub code", shown.userCode))) }
                    Browser.open(context, shown.verificationUri)
                },
            )
            FinePrint("PocketIDE asks for your repositories and Codespaces only. Waiting for your approval…")
        } else if (problem == null) {
            FinePrint("Asking GitHub for a code…")
        }
        problem?.let {
            Gap(16.dp)
            NoticeCard(it, Tone.ERROR)
            Gap(12.dp)
            SecondaryAction("Try again", onClick = { attempt++ })
        }
        Gap(16.dp)
        QuietAction("What PocketIDE can see", onClick = { onRead(DocsContent.PRIVACY_ID) })
        QuietAction("Use a different GitHub App", onClick = { editingApp = true })
    }
}

/** Polls until GitHub answers; returns the sentence to show, or null once signed in (the screen then moves on). */
private suspend fun waitForApproval(graph: AppGraph, code: DeviceCode): String? {
    var interval = code.intervalSeconds
    var answer: DevicePoll = DevicePoll.Pending
    while (answer == DevicePoll.Pending || answer is DevicePoll.SlowDown) {
        delay(interval * MS_PER_SECOND)
        answer = try {
            graph.gitHubAuth.poll(code)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: IOException) {
            // A dropped connection while the owner is in Chrome: keep waiting.
            DevicePoll.Pending
        } catch (e: Exception) {
            DevicePoll.Failed(e.message ?: SIGN_IN_FAILED)
        }
        (answer as? DevicePoll.SlowDown)?.let { interval = it.intervalSeconds }
    }
    return when (val final = answer) {
        is DevicePoll.Connected -> null
        DevicePoll.Denied -> "You did not approve PocketIDE on GitHub. Try again when you are ready."
        DevicePoll.Expired -> "The code expired. Get a new one."
        is DevicePoll.Failed -> final.why
        DevicePoll.Pending, is DevicePoll.SlowDown -> null
    }
}

private const val MS_PER_SECOND = 1_000L

/**
 * The GitHub App PocketIDE signs in through, for an owner whose APK carries none (the first build,
 * before the App existed) or who typed it wrong: its public client ID and name, kept on this phone.
 */
@Composable
private fun GitHubAppForm(graph: AppGraph, first: Boolean, onSaved: () -> Unit, onBack: (() -> Unit)?, onRead: (String) -> Unit) {
    ShellPage {
        onBack?.let { QuietAction("Back", onClick = it) }
        ScreenTitle(
            Icons.Outlined.Lan,
            if (first) "Connect your GitHub App" else "Change the GitHub App",
            if (first) "This copy of PocketIDE was built without one. Enter your App's public details once." else null,
        )
        Gap(24.dp)
        GitHubAppFields(graph, onSaved = { onSaved() })
        QuietAction("How to make the GitHub App", onClick = { onRead(DocsContent.OWNER_SET_UP_ID) })
    }
}

/** The App's two public details, checked before they are kept; [onSaved] learns whether the App changed. */
@Composable
internal fun GitHubAppFields(graph: AppGraph, onSaved: (appChanged: Boolean) -> Unit) {
    val choice = remember { gitHubAppChoice(graph) }
    val entered = remember { choice.entered() }
    var clientId by remember { mutableStateOf(entered?.clientId.orEmpty()) }
    var slug by remember { mutableStateOf(entered?.slug.orEmpty()) }
    var errors by remember { mutableStateOf<GitHubAppSave.Invalid?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Client ID") },
            singleLine = true,
            isError = errors?.clientId != null,
            supportingText = errors?.clientId?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = slug,
            onValueChange = { slug = it },
            label = { Text("App name in its address (github.com/apps/…)") },
            singleLine = true,
            isError = errors?.slug != null,
            supportingText = errors?.slug?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryAction(
            "Save",
            onClick = {
                when (val saved = choice.save(clientId, slug)) {
                    is GitHubAppSave.Invalid -> errors = saved
                    is GitHubAppSave.Saved -> onSaved(saved.appChanged)
                }
            },
        )
    }
}

/**
 * The App's installation decides which repositories PocketIDE may use. Checked again whenever
 * the owner comes back from GitHub's page.
 */
@Composable
internal fun AllowReposScreen(graph: AppGraph, login: String, onDone: () -> Unit) {
    val context = LocalContext.current
    var installed by remember { mutableStateOf<Boolean?>(null) }
    var checks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks++ }
    LaunchedEffect(checks) {
        installed = try {
            graph.gitHub.installedOn(login)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            null
        }
    }
    ShellPage {
        ScreenTitle(Icons.Outlined.Lan, "Choose your repositories", "Pick which repositories PocketIDE may use. Only these can get a cloud computer.")
        Gap(20.dp)
        NoticeCard(
            "Choose \"All repositories\" so the projects you make in PocketIDE work at once. PocketIDE never deletes " +
                "a repository or changes who can see it.",
            Tone.NEUTRAL,
        )
        Gap(20.dp)
        when (installed) {
            true -> {
                NoticeCard("PocketIDE can use your repositories.", Tone.OK)
                Gap(20.dp)
                PrimaryAction("Continue", onClick = onDone)
                QuietAction("Change which repositories", onClick = { Browser.open(context, graph.gitHubAuth.installUrl()) })
            }
            else -> {
                PrimaryAction("Choose on GitHub", onClick = { Browser.open(context, graph.gitHubAuth.installUrl()) })
                SecondaryAction("Check again", onClick = { checks++ })
                QuietAction("Skip for now", onClick = onDone)
            }
        }
    }
}

private const val SIGN_IN_FAILED = "GitHub sign-in did not start. Check the internet connection and try again."
