package com.pocketide.ui.tools

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.MainActivity
import com.pocketide.agents.Agent
import com.pocketide.cloudshell.CloudShell
import com.pocketide.cloudshell.IdePlace
import com.pocketide.graph
import com.pocketide.ui.components.AgentLogo
import com.pocketide.ui.screens.cloudshell.signInSteps
import com.pocketide.ui.shell.BrandMark
import com.pocketide.ui.theme.PocketTheme
import com.pocketide.ui.web.Browser
import com.pocketide.ui.web.IdeTab

/**
 * PocketIDE's tools, over the Chrome tab an agent's VS Code is in: the tools button in the tab's
 * bar opens them, a tap outside (or Back) hides them again. Every agent with its logo, Cloud
 * Shell's terminal and files, back to PocketIDE, and how each agent signs in. Chrome's menu (⋮)
 * comes here too, with a place already picked. Not exported: only PocketIDE's own tab can open it.
 */
class IdeToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IdePlace.named(intent.getStringExtra(IdeTab.EXTRA_PLACE))?.let {
            go(it)
            return
        }
        enableEdgeToEdge()
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle()
            PocketTheme(settings.theme, settings.dynamicColor) {
                ToolsSheet(onPlace = ::go, onHome = ::home, onClose = ::finish)
            }
        }
    }

    /** PocketIDE comes to the front, closing this tab, and opens [place] in a new one. */
    private fun go(place: IdePlace) {
        IdeTab.openNext(place)
        home()
    }

    private fun home() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}

@Composable
internal fun ToolsSheet(onPlace: (IdePlace) -> Unit, onHome: () -> Unit, onClose: () -> Unit) {
    var help by rememberSaveable { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM))
            .clickable(interactionSource = null, indication = null, onClickLabel = "Close the tools", onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                // A tap on the sheet itself stays on the sheet (and is no button for TalkBack).
                .pointerInput(Unit) { detectTapGestures {} },
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(28.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "PocketIDE tools",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
                }
                Label("Agents")
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Agent.entries.forEach { agent ->
                        Tile(agent.displayName, onClick = { onPlace(IdePlace.of(agent)) }) { AgentLogo(agent, size = 48.dp) }
                    }
                }
                Label("Cloud Shell")
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Tile("Terminal", onClick = { onPlace(IdePlace.TERMINAL) }) { Symbol(Icons.Outlined.Terminal) }
                    Tile("Files", onClick = { onPlace(IdePlace.FILES) }) { Symbol(Icons.Outlined.Folder) }
                    Tile("PocketIDE", onClick = onHome) { Symbol(Icons.Outlined.Home) }
                }
                TextButton(onClick = { help = !help }) {
                    Text("How each agent signs in", modifier = Modifier.weight(1f, fill = false))
                    Icon(if (help) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                }
                AnimatedVisibility(help) { SignInHelp() }
                Text(
                    "Chrome's menu (⋮) switches agents too. The arrow at the top left returns to PocketIDE.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SignInHelp() {
    val context = LocalContext.current
    Column(Modifier.padding(end = 8.dp)) {
        Agent.entries.forEach { agent ->
            Text(agent.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Text(signInSteps(agent), style = MaterialTheme.typography.bodyMedium)
            if (agent == Agent.CODEX) {
                TextButton(onClick = { Browser.open(context, CloudShell.CODEX_DEVICE_SIGN_IN) }) { Text("Open ChatGPT settings") }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** A logo or icon with its name under it, one tap to open. */
@Composable
private fun Tile(name: String, onClick: () -> Unit, picture: @Composable () -> Unit) {
    Column(
        Modifier
            .width(96.dp)
            .heightIn(min = 88.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = "Open $name", onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        picture()
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Symbol(icon: ImageVector) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

private const val SCRIM = 0.45f
