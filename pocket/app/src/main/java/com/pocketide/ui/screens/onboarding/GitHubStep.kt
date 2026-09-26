package com.pocketide.ui.screens.onboarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.github.GitHubAccount
import com.pocketide.github.gitHubAppChoice
import com.pocketide.ui.components.Tone
import com.pocketide.ui.shell.CheckCard
import com.pocketide.ui.shell.CheckItem
import com.pocketide.ui.shell.DeviceSignIn
import com.pocketide.ui.shell.External
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.GitHubAccountCard
import com.pocketide.ui.shell.GitHubConnectPanel
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.OnboardingStep
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.QuietAction
import com.pocketide.ui.shell.ScreenTitle
import com.pocketide.ui.shell.SecondaryAction
import com.pocketide.ui.shell.SectionLabel
import com.pocketide.ui.shell.ShellPage
import com.pocketide.ui.shell.StepHeader
import com.pocketide.ui.shell.rememberGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Step 1: GitHub through the PocketIDE GitHub App's device flow, and the App installed on the
 * account. The private `pocketide-keyring` repository is made in the next step, with the key.
 */
@Composable
fun GitHubStepScreen(onDone: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = graph.gitHubAuth
    val account by auth.account.collectAsStateWithLifecycle()
    val appChoice = remember(graph) { gitHubAppChoice(graph) }
    val appForm = remember { GitHubAppForm() }
    val signIn = DeviceSignIn.of(graph)
    // An App the owner enters below lands in the settings; reading them here redraws the step then.
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val configured = remember(settings.gitHubAppClientId) { auth.configured }
    val appEntered = remember(settings.gitHubAppClientId) { appChoice.entered() != null }
    // A typo GitHub rejects must be fixable here: Settings is not reachable before set-up ends.
    var editingApp by remember { mutableStateOf(false) }
    // The poll's answer shows at once, even before the module publishes the account.
    var justConnected by remember { mutableStateOf<GitHubAccount?>(null) }
    val shown = account ?: justConnected

    ShellPage {
        StepHeader(OnboardingStep.GITHUB)
        Gap(24.dp)
        ScreenTitle(
            icon = Icons.Outlined.Code,
            title = "Connect GitHub",
            subtitle = "Your code lives here, in private repositories. Every finished task is saved automatically.",
        )
        SectionLabel("Goes to GitHub")
        CheckCard(
            listOf(
                CheckItem("Your projects' code", "A private repository each, saved after every task"),
                CheckItem("Mac, Windows and iPhone builds", "They run on GitHub Actions, not on this phone"),
            ),
        )
        SectionLabel("Never goes to GitHub")
        CheckCard(
            listOf(
                CheckItem("Chats and AI memory", "Google Drive, encrypted · the next step", good = false),
                CheckItem("Passwords and agent sign-ins", "They stay on this phone", good = false),
            ),
        )
        Gap(24.dp)

        when {
            !configured || editingApp -> GitHubAppSetUp(
                form = appForm,
                choice = appChoice,
                openUrl = { External.openUrl(context, it) },
                onSaved = {
                    editingApp = false
                    signIn.start()
                },
            )
            shown != null -> {
                SectionLabel("Check before you go on")
                GitHubAccountCard(shown)
                Gap(12.dp)
                NoticeCard(
                    "A private repository named pocketide-keyring will be made in your account in the next step. " +
                        "It holds one half of your chats' key, never your code, and GitHub Actions stay off there.",
                )
                Gap(12.dp)
                AppInstallActions(
                    login = shown.login,
                    openInstallPage = { External.openUrl(context, auth.installUrl()) },
                    onDone = onDone,
                )
                QuietAction(
                    "Use another GitHub account",
                    onClick = {
                        justConnected = null
                        scope.launch {
                            try {
                                auth.signOut()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Signing out only forgets tokens on this phone; a failure leaves them unused.
                            }
                        }
                    },
                )
            }
            else -> {
                GitHubConnectPanel(
                    signIn = signIn,
                    openUrl = { External.openUrl(context, it) },
                    onConnected = { justConnected = it },
                )
                if (appEntered) {
                    QuietAction(
                        "Change the GitHub App's details",
                        onClick = {
                            signIn.reset()
                            appChoice.current().let { app ->
                                appForm.clientId = app.clientId
                                appForm.slug = app.slug
                            }
                            editingApp = true
                        },
                    )
                }
            }
        }
    }
}

/**
 * Signing in does not install PocketIDE's GitHub App, and the next step needs it to make the
 * keyring. While it is missing, installing it is the way on; the answer is asked again each time
 * the owner comes back from GitHub.
 */
@Composable
private fun AppInstallActions(login: String, openInstallPage: () -> Unit, onDone: () -> Unit) {
    val graph = rememberGraph()
    var install by remember(login) { mutableStateOf(AppInstall.CHECKING) }
    var asked by remember(login) { mutableIntStateOf(0) }
    LaunchedEffect(login, asked) { install = appInstall(graph.gitHub, login) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (install.askAgainOnReturn && install != AppInstall.CHECKING) asked++ }

    when (install) {
        AppInstall.MISSING -> {
            NoticeCard(
                "PocketIDE is not installed on your GitHub account yet. Install it, choosing all repositories " +
                    "or only the ones PocketIDE may use, then come back here.",
                Tone.WARN,
            )
            Gap(12.dp)
            PrimaryAction("Install PocketIDE on your GitHub", onClick = openInstallPage)
            Gap(12.dp)
            SecondaryAction("Continue", onClick = onDone, enabled = false)
        }
        AppInstall.UNKNOWN -> {
            NoticeCard("Could not check that PocketIDE is installed on your GitHub. The next step tells you if it is not.")
            Gap(12.dp)
            SecondaryAction("Check again", onClick = { asked++ })
            Gap(12.dp)
            PrimaryAction("Continue", onClick = onDone)
        }
        AppInstall.CHECKING, AppInstall.INSTALLED -> {
            SecondaryAction("Choose which repositories PocketIDE may use", onClick = openInstallPage)
            Gap(12.dp)
            PrimaryAction("Continue", onClick = onDone, enabled = install.canContinue, busy = install == AppInstall.CHECKING)
        }
    }
}
