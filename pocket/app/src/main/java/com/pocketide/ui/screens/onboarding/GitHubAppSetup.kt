package com.pocketide.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketide.github.GitHubAppChoice
import com.pocketide.github.GitHubAppSave
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.OutlinedCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.SecondaryAction
import com.pocketide.ui.shell.SectionLabel

/** The two public values that name the owner's GitHub App, as typed, with the last save's problems. */
@Stable
internal class GitHubAppForm(clientId: String = "", slug: String = "") {
    var clientId by mutableStateOf(clientId)
    var slug by mutableStateOf(slug)
    private var problems by mutableStateOf<GitHubAppSave.Invalid?>(null)

    val clientIdProblem: String? get() = problems?.clientId
    val slugProblem: String? get() = problems?.slug

    /** Saves when both values are valid; otherwise shows why under each wrong field. */
    fun save(choice: GitHubAppChoice): GitHubAppSave.Saved? = when (val result = choice.save(clientId, slug)) {
        is GitHubAppSave.Saved -> result.also { problems = null }
        is GitHubAppSave.Invalid -> null.also { problems = result }
    }
}

/**
 * Set-up when this copy was built before the owner made the GitHub App: how to make it, then its
 * client ID and name. Continue saves them and starts the sign-in.
 */
@Composable
internal fun GitHubAppSetUp(form: GitHubAppForm, choice: GitHubAppChoice, openUrl: (String) -> Unit, onSaved: () -> Unit) {
    SectionLabel("First, make your GitHub App")
    GitHubAppHowTo(openUrl)
    Gap(16.dp)
    GitHubAppFields(form)
    Gap(16.dp)
    PrimaryAction("Continue", onClick = { if (form.save(choice) != null) onSaved() })
}

/** How to make the App on GitHub, in three short steps, and a link to GitHub's form. */
@Composable
internal fun GitHubAppHowTo(openUrl: (String) -> Unit) {
    OutlinedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HOW_TO.forEachIndexed { index, step ->
                Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Gap(12.dp)
    SecondaryAction("Make the App on GitHub", onClick = { openUrl(GitHubAppChoice.NEW_APP_PAGE) })
}

@Composable
internal fun GitHubAppFields(form: GitHubAppForm) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = form.clientId,
            onValueChange = { form.clientId = it },
            label = { Text("Client ID") },
            placeholder = { Text("Iv23…") },
            singleLine = true,
            isError = form.clientIdProblem != null,
            supportingText = form.clientIdProblem?.let { { Text(it) } },
            keyboardOptions = PLAIN_TEXT,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.slug,
            onValueChange = { form.slug = it },
            label = { Text("App name from its address") },
            placeholder = { Text("github.com/apps/name") },
            singleLine = true,
            isError = form.slugProblem != null,
            supportingText = form.slugProblem?.let { { Text(it) } },
            keyboardOptions = PLAIN_TEXT.copy(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val PLAIN_TEXT = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Ascii,
    imeAction = ImeAction.Next,
)

private val HOW_TO = listOf(
    "On GitHub, make a new GitHub App: any name, homepage https://github.com, Webhook \"Active\" unticked, " +
        "\"Enable Device Flow\" ticked.",
    "Repository permissions: Actions, Administration, Contents, Pull requests, Secrets and Workflows " +
        "\"Read and write\"; Metadata \"Read-only\". Account permissions: Plan \"Read-only\".",
    "Create it, then copy its Client ID and the name at the end of its address, github.com/apps/name, here.",
)
