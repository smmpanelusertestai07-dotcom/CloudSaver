package com.pocketide.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.BuildConfig
import com.pocketide.PocketApp
import com.pocketide.cloud.ComputerService
import com.pocketide.core.ThemeMode
import com.pocketide.docs.DocLinks
import com.pocketide.docs.DocsContent
import com.pocketide.graph
import com.pocketide.ui.components.DialogBody
import com.pocketide.ui.components.Formats
import com.pocketide.ui.components.GitHubLogo
import com.pocketide.ui.components.KeepTypedInput
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.lock.canLock
import com.pocketide.ui.screens.onboarding.GitHubAppFields
import com.pocketide.ui.web.Browser
import kotlinx.coroutines.launch

/** The few choices PocketIDE has, each with what it does. Defaults are the private, safe ones. */
@Composable
fun SettingsScreen(onYourData: () -> Unit, onHelp: () -> Unit, onHelpPage: (String) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val account by graph.gitHubAuth.account.collectAsStateWithLifecycle()
    val lockable = (context as? FragmentActivity)?.let(::canLock) == true
    var signingOut by remember { mutableStateOf(false) }
    var changingApp by remember { mutableStateOf(false) }
    val update = graph.settings::update

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        SectionCard("Appearance") {
            InfoItem(Icons.Outlined.DarkMode, "Theme", null)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = listOf(ThemeMode.SYSTEM to "Phone", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = settings.theme == mode,
                        onClick = { update { it.copy(theme = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    ) { Text(label) }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Toggle(Icons.Outlined.Palette, "Wallpaper colours", "Colours from your wallpaper instead of PocketIDE's violet", settings.dynamicColor) { on ->
                    update { it.copy(dynamicColor = on) }
                }
            }
        }

        SectionCard("New cloud computers") {
            Text(
                "GitHub sets these when it makes a computer, so they apply to computers made from now on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Choice(
                icon = Icons.Outlined.Memory,
                title = "Machine",
                options = MACHINES,
                selected = settings.newComputer.machine,
                label = { it.second },
                value = { it.first },
            ) { machine -> update { it.copy(newComputer = it.newComputer.copy(machine = machine)) } }
            Choice(
                icon = Icons.Outlined.Schedule,
                title = "Stop when idle for",
                options = IDLE_MINUTES,
                selected = settings.newComputer.idleMinutes,
                label = Formats::minutes,
                value = { it },
            ) { minutes -> update { it.copy(newComputer = it.newComputer.copy(idleMinutes = minutes)) } }
            Choice(
                icon = Icons.Outlined.DeleteSweep,
                title = "Delete when unused for",
                options = KEEP_DAYS,
                selected = settings.newComputer.keepDays,
                label = Formats::days,
                value = { it },
            ) { days -> update { it.copy(newComputer = it.newComputer.copy(keepDays = days)) } }
        }

        SectionCard("Computer screen") {
            Toggle(Icons.Outlined.Keyboard, "Keyboard keys", "Esc, Tab, Ctrl, arrows and Send above the keyboard", settings.keyBar) { on ->
                update { it.copy(keyBar = on) }
            }
            Toggle(
                Icons.Outlined.Sync,
                "Stay connected in the background",
                "Keeps the page open with a notification and a Stop button. Agents work on GitHub either way.",
                settings.stayConnected,
            ) { on ->
                update { it.copy(stayConnected = on) }
                if (!on) ComputerService.disconnect(context)
            }
        }

        SectionCard("Privacy and security") {
            Toggle(
                Icons.Outlined.Fingerprint,
                "App lock",
                if (lockable) "Asks for your screen lock, and hides the app in Recents" else "Set a screen lock in Android's settings to use this",
                settings.appLock && lockable,
                enabled = lockable,
            ) { on ->
                // The owner is here already: the lock starts from the next time the app opens.
                if (on) (context.applicationContext as PocketApp).appLock.unlock()
                update { it.copy(appLock = on) }
            }
            Link(Icons.Outlined.Storage, "Your data", "Where everything is, and deleting it", onYourData)
            Link(Icons.Outlined.AlternateEmail, "Keep your email private", "On GitHub, so your commits do not show it", {
                Browser.open(context, DocLinks.GITHUB_EMAILS)
            })
        }

        SectionCard("GitHub") {
            ListItem(
                headlineContent = { Text(account?.login ?: "Not signed in") },
                supportingContent = { Text("Your code, cloud computers and builds live in this account") },
                leadingContent = { GitHubLogo() },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
            Link(Icons.Outlined.FolderShared, "Repositories PocketIDE may use", "Choose on GitHub", {
                Browser.open(context, graph.gitHubAuth.installUrl())
            })
            Link(Icons.Outlined.Apps, "PocketIDE's access on GitHub", "See or remove it on GitHub", {
                Browser.open(context, graph.gitHubAuth.authorizationsUrl())
            })
            Link(Icons.Outlined.Key, "GitHub App", "The App PocketIDE signs in through", { changingApp = true })
            Link(Icons.Outlined.Password, "Codespaces secrets and Settings Sync", "Keys your tools need; keep Settings Sync off", {
                Browser.open(context, DocLinks.CODESPACES_SETTINGS)
            })
            Link(Icons.Outlined.Savings, "Spending limit", "Keep GitHub's budget at \$0 so you are never charged", {
                Browser.open(context, DocLinks.BUDGETS)
            })
            Link(Icons.AutoMirrored.Outlined.Logout, "Sign out", "The cloud computers keep running until GitHub stops them", { signingOut = true })
        }

        SectionCard("About") {
            Link(Icons.AutoMirrored.Outlined.MenuBook, "Help", "How PocketIDE works, and answers", onHelp)
            Link(Icons.Outlined.Gavel, "Terms of use", null, { onHelpPage(DocsContent.TERMS_ID) })
            Link(Icons.Outlined.PrivacyTip, "Privacy policy", null, { onHelpPage(DocsContent.PRIVACY_ID) })
            Link(Icons.AutoMirrored.Outlined.Article, "Open-source licences", null, { onHelpPage(DocsContent.NOTICES_ID) })
            InfoItem(Icons.Outlined.Info, "PocketIDE ${BuildConfig.VERSION_NAME}", DocsContent.TAGLINE)
        }
    }

    if (changingApp) {
        AlertDialog(
            onDismissRequest = { changingApp = false },
            title = { Text("GitHub App") },
            text = {
                DialogBody {
                    Text("Its public client ID and the name in its address. A different App signs you out; sign in again with it.")
                    GitHubAppFields(graph) { appChanged ->
                        changingApp = false
                        if (appChanged) {
                            ComputerService.disconnect(context)
                            graph.computerPage.release()
                            scope.launch { graph.gitHubAuth.signOut() }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { changingApp = false }) { Text("Close") } },
            properties = KeepTypedInput,
        )
    }
    if (signingOut) {
        AlertDialog(
            onDismissRequest = { signingOut = false },
            title = { Text("Sign out of GitHub?") },
            text = { Text("PocketIDE forgets your sign-in on this phone. Your code, cloud computers and chats stay in your GitHub account.") },
            confirmButton = {
                TextButton(onClick = {
                    signingOut = false
                    ComputerService.disconnect(context)
                    graph.computerPage.release()
                    scope.launch { graph.gitHubAuth.signOut() }
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { signingOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InfoItem(icon: ImageVector, title: String, detail: String?) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

@Composable
private fun Link(icon: ImageVector, title: String, detail: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Toggle(icon: ImageVector, title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

/** A setting with a few fixed answers: its current one shown, the rest in a dialog. */
@Composable
private fun <T, V> Choice(
    icon: ImageVector,
    title: String,
    options: List<T>,
    selected: V,
    label: (T) -> String,
    value: (T) -> V,
    onSelect: (V) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { value(it) == selected }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(current?.let(label) ?: selected.toString()) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    options.forEach { option ->
                        val chosen = value(option) == selected
                        Row(
                            Modifier.fillMaxWidth().selectable(chosen, role = Role.RadioButton) {
                                onSelect(value(option))
                                open = false
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chosen, onClick = null)
                            Text(label(option), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

/** GitHub's machine names; the empty name lets GitHub pick its smallest, which is the default. */
private val MACHINES = listOf(
    "" to "2 cores, 8 GB RAM (uses the fewest hours)",
    "standardLinux32gb" to "4 cores, 16 GB RAM (uses hours twice as fast)",
)
private val IDLE_MINUTES = listOf(15, 30, 60, 120, 240)
private val KEEP_DAYS = listOf(7, 14, 30)
