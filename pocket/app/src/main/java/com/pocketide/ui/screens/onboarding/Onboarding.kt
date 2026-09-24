package com.pocketide.ui.screens.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import com.pocketide.ui.shell.BrandMark
import com.pocketide.ui.shell.External
import com.pocketide.ui.shell.FinePrint
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.LimitedScreens
import com.pocketide.ui.shell.Links
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.OnboardingStep
import com.pocketide.ui.shell.OutlinedCard
import com.pocketide.ui.shell.OutsideLink
import com.pocketide.ui.shell.PhoneFactsReader
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.RequirementRow
import com.pocketide.ui.shell.Requirements
import com.pocketide.ui.shell.ScreenTitle
import com.pocketide.ui.shell.SectionLabel
import com.pocketide.ui.shell.ShellPage
import com.pocketide.ui.shell.StepHeader
import com.pocketide.ui.shell.rememberGraph
import com.pocketide.vault.KeyState

/**
 * First run: Welcome, then GitHub, Google Drive, the computer and the privacy checklist. It
 * resumes at the first unfinished step if the app was closed half way, and back goes one step
 * back. The last step marks set-up as done, which opens the app.
 */
@Composable
fun OnboardingFlow() {
    val graph = rememberGraph()
    val account by graph.gitHubAuth.account.collectAsStateWithLifecycle()
    val email by graph.driveAuth.email.collectAsStateWithLifecycle()
    val key by graph.vault.state.collectAsStateWithLifecycle()
    var stepName by rememberSaveable {
        val keyReady = key == KeyState.Ready || key == KeyState.OnlyOnPhone
        mutableStateOf(OnboardingStep.resumeAt(account != null, email != null, keyReady).name)
    }
    val step = OnboardingStep.valueOf(stepName)
    fun go(to: OnboardingStep) {
        stepName = to.name
    }

    LimitedScreens { nav ->
        BackHandler(enabled = step.previous() != null) { step.previous()?.let(::go) }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val sign = if (forward) 1 else -1
                (slideInHorizontally(tween(280)) { it / 5 * sign } + fadeIn(tween(280)))
                    .togetherWith(slideOutHorizontally(tween(200)) { -it / 5 * sign } + fadeOut(tween(200)))
            },
            label = "onboarding",
        ) { shown ->
            when (shown) {
                OnboardingStep.WELCOME -> WelcomeScreen(onContinue = { go(OnboardingStep.GITHUB) }, onOpenHelp = nav::help)
                OnboardingStep.GITHUB -> GitHubStepScreen(onDone = { go(OnboardingStep.DRIVE) })
                OnboardingStep.DRIVE -> DriveStepScreen(onDone = { go(OnboardingStep.COMPUTER) })
                OnboardingStep.COMPUTER -> ComputerStepScreen(onDone = { go(OnboardingStep.PRIVACY) })
                OnboardingStep.PRIVACY -> PrivacyChecklistScreen(onDone = {})
            }
        }
    }
}

/** What PocketIDE is, whether this phone is up to it, and the terms. */
@Composable
fun WelcomeScreen(onContinue: () -> Unit, onOpenHelp: (String) -> Unit = {}) {
    val context = LocalContext.current
    val graph = rememberGraph()
    val rows = remember { Requirements.rows(PhoneFactsReader.read(context, graph)) }

    ShellPage {
        Gap(12.dp)
        BrandMark(size = 80.dp)
        Gap(24.dp)
        Text("PocketIDE", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        Gap(12.dp)
        WelcomeLine("Claude Code, Codex and Antigravity, full screen on your phone.")
        WelcomeLine("Your code goes to your private GitHub. Chats are encrypted into your Google Drive.")
        WelcomeLine("No server of ours in between: only your phone, your GitHub and your Drive.")

        SectionLabel("This phone")
        OutlinedCard {
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                RequirementLine(row)
            }
        }
        if (!Requirements.allMet(rows)) {
            Gap(12.dp)
            NoticeCard("Something above is below the minimum. Fix it before setting up the computer, or agents may not run.", Tone.WARN)
        }

        Gap(24.dp)
        Text(
            "Continuing means you accept the Terms. The Privacy policy says what goes where.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onOpenHelp("terms") }) { Text("Terms") }
            TextButton(onClick = { onOpenHelp("privacy") }) { Text("Privacy policy") }
        }
        Gap(12.dp)
        PrimaryAction("Get started", onClick = onContinue)
        FinePrint("The computer is one download of about 1.5 GB. Wi-Fi is best.")
    }
}

@Composable
private fun WelcomeLine(text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Box(
            Modifier
                .padding(top = 9.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RequirementLine(row: RequirementRow) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(toneColor(row.tone)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(row.value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
            }
            if (row.note != null) {
                Text(row.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Each AI company's training switch and 2-step sign-in on Google and GitHub, as links. Ticking
 * a row is the owner's own note; nothing is read from those accounts.
 */
@Composable
fun PrivacyChecklistScreen(onDone: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    var ticked by rememberSaveable { mutableStateOf("") }
    val done = ticked.split(',').filter { it.isNotEmpty() }.toSet()
    val required = Links.privacyChecklist.filterNot { it.optional }.map { it.id }

    ShellPage {
        StepHeader(OnboardingStep.PRIVACY, required = false)
        Gap(24.dp)
        ScreenTitle(
            icon = Icons.Outlined.PrivacyTip,
            title = "Privacy checklist",
            subtitle = "The agents run on your phone, but what you ask, and the code they read, goes to each AI company. " +
                "These switches decide what they keep.",
        )
        SectionLabel("Open each page, then tick it")
        OutlinedCard {
            Links.privacyChecklist.forEachIndexed { index, link ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ChecklistRow(
                    link = link,
                    checked = link.id in done,
                    onToggle = { on -> ticked = (if (on) done + link.id else done - link.id).joinToString(",") },
                    onOpen = { External.openUrl(context, link.url) },
                )
            }
        }
        Gap(16.dp)
        NoticeCard("Never paste passwords or keys into a chat: they would go to the AI company. Put them in Secrets instead.")
        Gap(24.dp)
        PrimaryAction(
            text = "Finish set-up",
            onClick = {
                val allDone = required.all { it in done }
                graph.settings.update { it.copy(onboardingDone = true, privacyChecklistDone = allDone) }
                onDone()
            },
        )
        FinePrint("These links stay in Settings → Manage your data.")
    }
}

@Composable
internal fun ChecklistRow(link: OutsideLink, checked: Boolean, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics { stateDescription = if (checked) "Done" else "Not done" },
        )
        Column(
            Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = "Open page") { onOpen() }
                .padding(vertical = 4.dp),
        ) {
            Text(link.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(link.what, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onOpen) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open ${link.title}", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
