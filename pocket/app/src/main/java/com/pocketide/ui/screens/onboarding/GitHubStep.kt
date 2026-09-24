package com.pocketide.ui.screens.onboarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.github.GitHubAccount
import com.pocketide.ui.components.Tone
import com.pocketide.ui.shell.CheckCard
import com.pocketide.ui.shell.CheckItem
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
 * Step 1: GitHub through the PocketIDE GitHub App's device flow. After sign-in the GitHub module
 * creates the private `pocketide-keyring` repository in the background.
 */
@Composable
fun GitHubStepScreen(onDone: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = graph.gitHubAuth
    val account by auth.account.collectAsStateWithLifecycle()
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
            !auth.configured -> NoticeCard(
                title = "GitHub sign-in is not set up in this copy",
                text = "The app's owner adds the PocketIDE GitHub App's client ID when building the app. " +
                    "This copy was built without it, so it cannot connect to GitHub. Install a release build instead.",
                tone = Tone.WARN,
            )
            shown != null -> {
                SectionLabel("Check before you go on")
                GitHubAccountCard(shown)
                Gap(12.dp)
                NoticeCard(
                    "A private repository named pocketide-keyring is being made in your account. " +
                        "It holds one half of your chats' key, never your code, and GitHub Actions stay off there.",
                )
                Gap(12.dp)
                SecondaryAction(
                    "Choose which repositories PocketIDE may use",
                    onClick = { External.openUrl(context, auth.installUrl()) },
                )
                Gap(12.dp)
                PrimaryAction("Continue", onClick = onDone)
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
            else -> GitHubConnectPanel(
                auth = auth,
                openUrl = { External.openUrl(context, it) },
                onConnected = { justConnected = it },
            )
        }
    }
}
