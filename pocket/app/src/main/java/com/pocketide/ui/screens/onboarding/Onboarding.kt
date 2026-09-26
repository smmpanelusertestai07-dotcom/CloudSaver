package com.pocketide.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.AppGraph
import com.pocketide.agents.Agent
import com.pocketide.docs.DocsContent
import com.pocketide.ui.components.AgentLogo
import com.pocketide.ui.components.GitHubLogo
import com.pocketide.ui.components.VsCodeLogo
import com.pocketide.ui.screens.help.HelpPageScreen
import com.pocketide.ui.shell.BrandMark
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.IconTile
import com.pocketide.ui.shell.ShellPage

/**
 * First run: the welcome with the terms, GitHub sign-in, then the repositories PocketIDE may use.
 * Each step follows from what is already true, so leaving half-way resumes at the right step.
 */
@Composable
fun Onboarding(graph: AppGraph) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val account by graph.gitHubAuth.account.collectAsStateWithLifecycle()
    var reading by remember { mutableStateOf<String?>(null) }
    val page = reading
    val signedIn = account
    when {
        page != null -> HelpPageScreen(id = page, onBack = { reading = null }, onOpen = { reading = it })
        settings.termsAccepted < DocsContent.TERMS_VERSION -> WelcomeScreen(
            onRead = { reading = it },
            onContinue = { graph.settings.update { it.copy(termsAccepted = DocsContent.TERMS_VERSION) } },
        )
        signedIn == null -> SignInScreen(graph, onRead = { reading = it })
        else -> AllowReposScreen(graph, signedIn.login, onDone = { graph.settings.update { it.copy(onboardingDone = true) } })
    }
}

@Composable
internal fun WelcomeScreen(onRead: (String) -> Unit, onContinue: () -> Unit) {
    ShellPage {
        Gap(8.dp)
        BrandMark(72.dp)
        Gap(20.dp)
        Text("PocketIDE", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text(DocsContent.TAGLINE, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Gap(28.dp)
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Feature(
                icon = { VsCodeLogo(size = 30.dp) },
                title = "Your own cloud computer",
                text = "A GitHub Codespace: Ubuntu and real VS Code on GitHub's servers, only for you.",
            )
            Feature(
                icon = { AgentLogo(Agent.CLAUDE, size = 36.dp) },
                title = "Three official AI agents",
                text = "Claude Code by Anthropic, Codex by OpenAI and Antigravity by Google, each full screen.",
            )
            Feature(
                icon = { IconTile(Icons.Outlined.Lock, size = 40.dp) },
                title = "Private by default",
                text = "New projects are private repositories. This phone keeps only your sign-in and settings.",
            )
            Feature(
                icon = { GitHubLogo(size = 30.dp) },
                title = "One account: GitHub",
                text = "No server of ours and no other cloud. Code, computer and builds stay in your GitHub account.",
            )
        }
        Gap(28.dp)
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
            GitHubLogo(size = 20.dp)
            Spacer(Modifier.width(12.dp))
            Text("Continue with GitHub", style = MaterialTheme.typography.titleMedium)
        }
        Gap(12.dp)
        TermsLine(onRead)
    }
}

@Composable
private fun Feature(icon: @Composable () -> Unit, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TermsLine(onRead: (String) -> Unit) {
    val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold))
    val text = buildAnnotatedString {
        append("By continuing you agree to PocketIDE's ")
        withLink(LinkAnnotation.Clickable(DocsContent.TERMS_ID, link) { onRead(DocsContent.TERMS_ID) }) { append("Terms of use") }
        append(" and ")
        withLink(LinkAnnotation.Clickable(DocsContent.PRIVACY_ID, link) { onRead(DocsContent.PRIVACY_ID) }) { append("Privacy policy") }
        append(".")
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}
