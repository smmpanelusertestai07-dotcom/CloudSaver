package com.pocketide.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketide.github.GitHubAppChoice
import com.pocketide.ui.components.Tone
import com.pocketide.ui.screens.onboarding.GitHubAppFields
import com.pocketide.ui.screens.onboarding.GitHubAppForm
import com.pocketide.ui.screens.onboarding.GitHubAppHowTo
import com.pocketide.ui.shell.NoticeCard

/** The Advanced row's summary of which GitHub App sign-in goes through. */
internal fun gitHubAppSummary(choice: GitHubAppChoice): String {
    val entered = choice.entered()
    val app = choice.current()
    return when {
        entered != null -> "Yours: ${entered.slug}"
        app.configured -> "Built into this copy: ${app.slug.ifBlank { app.clientId }}"
        else -> "Not set. GitHub can't be connected until you add it"
    }
}

/**
 * The owner's own GitHub App, for a copy built without one or with another. [signedIn] warns that
 * a different App needs a new sign-in: tokens belong to the App that issued them.
 */
@Composable
internal fun GitHubAppDialog(
    choice: GitHubAppChoice,
    signedIn: Boolean,
    openUrl: (String) -> Unit,
    onSaved: (appChanged: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val entered = remember(choice) { choice.entered() }
    val form = remember { GitHubAppForm(entered?.clientId.orEmpty(), entered?.slug.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GitHub App") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "PocketIDE connects to GitHub through a GitHub App you own. Its client ID and name are public, " +
                        "and they are kept on this phone only.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                GitHubAppHowTo(openUrl)
                GitHubAppFields(form)
                if (signedIn) NoticeCard("A different App signs you out of GitHub here. Connect again right after.", Tone.WARN)
                if (entered != null && choice.hasBuiltIn) {
                    TextButton(onClick = { onSaved(choice.clear().appChanged) }) { Text("Use the App built into this copy") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { form.save(choice)?.let { onSaved(it.appChanged) } }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
