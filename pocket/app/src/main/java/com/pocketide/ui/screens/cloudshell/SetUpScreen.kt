package com.pocketide.ui.screens.cloudshell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.cloudshell.CloudShell
import com.pocketide.docs.DocsContent
import com.pocketide.graph
import com.pocketide.ui.components.ActionRow
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.Tone
import com.pocketide.ui.screens.help.HelpPageScreen
import com.pocketide.ui.shell.BrandMark
import com.pocketide.ui.shell.FinePrint
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.ShellPage
import com.pocketide.ui.web.Browser

/**
 * The set-up PocketIDE opens with until its computer, Google Cloud Shell, is ready: pick the Google
 * account, paste one command into Cloud Shell, say when it is done. It comes back by itself when
 * PocketIDE has not opened Cloud Shell for so long that Google may have deleted its home folder.
 */
@Composable
fun SetUpScreen() {
    val context = LocalContext.current
    val graph = context.graph
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    var opened by rememberSaveable { mutableStateOf(false) }
    var reading by remember { mutableStateOf<String?>(null) }
    val pick = rememberAccountPicker { name -> graph.settings.update { it.copy(cloudAccount = name) } }
    val account = settings.cloudAccount
    val unused = CloudShell.daysUnused(settings, graph.clock.now())

    reading?.let { page ->
        HelpPageScreen(id = page, onBack = { reading = null }, onOpen = { reading = it })
        return
    }
    ShellPage {
        BrandMark(56.dp)
        Gap(16.dp)
        Text("Set up your computer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "PocketIDE's computer is Google Cloud Shell: Google's own Linux computer, free with a Google account. Each " +
                "agent gets its own VS Code there, and your phone stays cool.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Gap(16.dp)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (settings.cloudScript.isNotBlank() && CloudShell.newerSetUp(settings)) {
                NoticeCard(
                    "This version of PocketIDE brings a newer set-up. Paste the command in Cloud Shell once more: it only adds " +
                        "what changed, and your projects and chats stay.",
                    title = "A newer set-up",
                )
            }
            if (settings.cloudSetUpAt != 0L && unused >= CloudShell.ASK_AGAIN_AFTER_DAYS) {
                NoticeCard(
                    "PocketIDE has not opened Cloud Shell for $unused days, and Google deletes its home folder after " +
                        "${CloudShell.DELETED_AFTER_DAYS} days without use. Run the set-up again: it only adds what is missing.",
                    tone = Tone.WARN,
                )
            }
            SectionCard("1. Google account") {
                Text(
                    if (account.isBlank()) {
                        "Pick the account Cloud Shell uses. A separate Google account just for development keeps your main " +
                            "account, mail and photos apart; your main account works too. Turn on 2-Step Verification."
                    } else {
                        "Cloud Shell opens with $account."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                ActionRow {
                    if (account.isBlank()) PrimaryAction("Choose account", onClick = pick) else OutlinedButton(onClick = pick) { Text("Change") }
                    TextButton(onClick = { Browser.open(context, CloudShell.NEW_ACCOUNT) }) { Text("Create a Google account") }
                }
            }
            SectionCard("2. Set up (once, about 5 minutes)") {
                Text(
                    "Copy the command, open Cloud Shell, long-press in its terminal and tap Paste (or paste from your keyboard's " +
                        "clipboard), then Enter. It installs VS Code and Claude Code, Codex and Antigravity, each checked before " +
                        "use, in your Cloud Shell home folder; from then on Cloud Shell starts them by itself. The first time, " +
                        "Google asks you to accept its terms for Google Cloud. If Chrome offers to turn on sync, tap No thanks: " +
                        "it is not needed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                CommandBox(CloudShell.setupCommand)
                ActionRow {
                    OutlinedButton(onClick = { copyText(context, CloudShell.setupCommand, "Cloud Shell set-up") }, enabled = account.isNotBlank()) {
                        Text("Copy command")
                    }
                    OutlinedButton(onClick = {
                        opened = true
                        openCloudShell(context, CloudShell.terminal(account))
                    }, enabled = account.isNotBlank()) { Text("Open Cloud Shell") }
                }
            }
            SectionCard("3. When Cloud Shell says Done") {
                Text("Come back here and tap Set-up is done.", style = MaterialTheme.typography.bodyMedium)
                PrimaryAction("Set-up is done", onClick = {
                    val now = graph.clock.now()
                    graph.settings.update { it.copy(cloudSetUpAt = now, cloudOpenedAt = now, cloudScript = CloudShell.SCRIPT_COMMIT) }
                }, enabled = account.isNotBlank() && (opened || settings.cloudSetUpAt != 0L))
            }
            FinePrint(
                "Free: 50 hours a week, at most 12 in one session; Cloud Shell stops about 40 minutes after you stop using it. " +
                    "Use it yourself, as Google intends: no miners, scanners or keep-awake tricks. Google's terms apply.",
            )
            ActionRow {
                TextButton(onClick = { Browser.open(context, CloudShell.TERMS) }) { Text("Google Cloud terms") }
                TextButton(onClick = { reading = DocsContent.COMPUTER_ID }) { Text("How it works") }
            }
        }
    }
}
