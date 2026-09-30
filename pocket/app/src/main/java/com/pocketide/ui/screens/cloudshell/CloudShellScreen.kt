package com.pocketide.ui.screens.cloudshell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketide.docs.DocsContent
import com.pocketide.ide.CloudShell
import com.pocketide.ui.components.ActionRow
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.Tone
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.web.Browser

/**
 * Google Cloud Shell, Google's own Linux computer: how to set it up with VS Code and the three
 * agents, where its data is and how to delete it, its free limits, and what keeps the Google
 * account safe. Cloud Shell opens in a Chrome tab: Google allows its sign-in only in a browser.
 */
@Composable
fun CloudShellScreen(onBack: () -> Unit, onHelpPage: (String) -> Unit) {
    val context = LocalContext.current
    val open = { url: String -> Browser.open(context, CloudShell.chooseAccountThen(url)) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Text("  Google Cloud Shell", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "Google's own Linux computer, free with a Google account. PocketIDE sets it up with VS Code and the three agents. " +
                "It runs on Google's servers, so the phone stays cool, and its downloads use Google's internet, not your data.",
            style = MaterialTheme.typography.bodyMedium,
        )

        SectionCard("1. Pick the Google account") {
            Text(
                "Google asks which account to use; nothing opens with an account by itself. A separate Google account just for " +
                    "development keeps your main account, mail and photos apart. Your main account works too. Either way, turn " +
                    "on 2-Step Verification.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { Browser.open(context, CloudShell.NEW_ACCOUNT) }) { Text("Create a Google account") }
        }

        SectionCard("2. Set up (once, about 5 minutes)") {
            Text(
                "Copy the command, open Cloud Shell, long-press in the terminal, Paste, then Enter. It installs VS Code and " +
                    "Claude Code, Codex and Antigravity from Open VSX in your Cloud Shell home, each checked first.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        CloudShell.setupCommand,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            ActionRow {
                PrimaryAction("Copy command", onClick = { copy(context, CloudShell.setupCommand) })
                OutlinedButton(onClick = { open(CloudShell.TERMINAL) }) { Text("Open Cloud Shell") }
            }
        }

        SectionCard("3. Every time") {
            Text(
                "Open Cloud Shell; VS Code starts by itself. Tap Web Preview (top right) > Preview on port ${CloudShell.PORT}: " +
                    "VS Code opens with the three agents. Sign in to each agent once. For Codex, turn on device code sign-in " +
                    "in ChatGPT (Settings > Security), then run codex login --device-auth in Cloud Shell.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { open(CloudShell.TERMINAL) }) { Text("Open Cloud Shell") }
        }

        SectionCard("Free limits") {
            Text(
                "50 hours a week (about 7 hours a day), 12 hours in one session, and it stops about 40 minutes after you stop " +
                    "using it: agents do not work on while you are away. 5 GB home folder; the setup uses about 1.6 GB. Google " +
                    "deletes the home folder after 120 days without use. Your hours: in Cloud Shell, Session information > " +
                    "Usage quota.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { Browser.open(context, CloudShell.LIMITS) }) { Text("Google's limits") }
        }

        SectionCard("Where your data is") {
            Text(
                "Only in your Cloud Shell home folder, which only your Google account opens: projects in ~/projects; chats and " +
                    "sign-ins in ~/.claude, ~/.codex and ~/.gemini; VS Code in ~/.local/share/code-server. What you ask an agent, " +
                    "and the code it reads, also goes to its company under your account there. It is not in Drive, Photos or " +
                    "your Google Cloud projects, and agent chats do not show on claude.ai or chatgpt.com.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("See it:", style = MaterialTheme.typography.titleSmall)
            ActionRow {
                OutlinedButton(onClick = { open(CloudShell.EDITOR) }) { Text("Cloud Shell editor") }
                OutlinedButton(onClick = { open(CloudShell.CONSOLE) }) { Text("Cloud console") }
                OutlinedButton(onClick = { Browser.open(context, CloudShell.MOBILE_APP) }) { Text("Google Cloud app") }
            }
            TextButton(onClick = { Browser.open(context, CloudShell.FILES) }) { Text("Download or upload files") }
        }

        SectionCard("Delete") {
            Text(
                "A file or project: delete it in VS Code. Everything: in Cloud Shell run sudo rm -rf \$HOME, then More > " +
                    "Restart; Cloud Shell starts again empty. Agent sign-ins: sign out in each agent, then remove the access in " +
                    "your Claude, ChatGPT and Google account settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { Browser.open(context, CloudShell.RESET) }) { Text("Google's reset steps") }
        }

        NoticeCard(
            "Use Cloud Shell yourself, while you work, as Google intends. No miners, scanners or tricks to keep it awake, and " +
                "never share a Web Preview link: breaking Google's rules can turn Cloud Shell off for your account.",
            tone = Tone.WARN,
            title = "Keep your Google account safe",
        )
        ActionRow {
            TextButton(onClick = { Browser.open(context, CloudShell.TERMS) }) { Text("Google Cloud terms") }
            TextButton(onClick = { Browser.open(context, CloudShell.PRIVACY) }) { Text("Google Cloud privacy") }
            TextButton(onClick = { onHelpPage(DocsContent.CLOUD_SHELL_ID) }) { Text("More in Help") }
        }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Cloud Shell setup", text))
    Toast.makeText(context, "Copied. Paste it in Cloud Shell's terminal.", Toast.LENGTH_SHORT).show()
}
