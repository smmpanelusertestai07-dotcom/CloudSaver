package com.pocketide.ui.screens.onboarding

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.core.Redact
import com.pocketide.limiter.EngineService
import com.pocketide.linux.ComputerSetup
import com.pocketide.linux.ComputerState
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import com.pocketide.ui.shell.FinePrint
import com.pocketide.ui.shell.Formats
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.OutlinedCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.QuietAction
import com.pocketide.ui.shell.rememberGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The plan's size of all set-up downloads (§2); the installer shows the exact total of its part once it starts. */
internal const val SETUP_DOWNLOAD_TEXT = "about 1.5 GB in all"

internal enum class Network { WIFI, MOBILE, OFFLINE }

/** When Home and the Computer screen offer the computer's set-up: it is missing, stopped, or on its way. */
object SetUpOffer {
    const val TITLE = "Set up the computer"

    fun shows(state: ComputerState): Boolean = needsOwner(state) || state is ComputerState.Installing

    /** Nothing that runs in the computer can start until the owner sets it up (again). */
    fun needsOwner(state: ComputerState): Boolean =
        state is ComputerState.NotInstalled || state is ComputerState.Broken
}

/**
 * The computer's set-up outside onboarding, as a card: the same Wi-Fi advice, the same question
 * before using mobile data, and the same progress as onboarding's step. Shown while
 * [SetUpOffer.shows] is true.
 */
@Composable
fun SetUpComputerCard(modifier: Modifier = Modifier) {
    val graph = rememberGraph()
    val state by graph.computer.state.collectAsStateWithLifecycle()
    OutlinedCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(SetUpOffer.TITLE, style = MaterialTheme.typography.titleMedium)
            Text(
                "A small Ubuntu computer inside PocketIDE runs the agents. Set-up downloads $SETUP_DOWNLOAD_TEXT.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SetUpComputer(state, onLater = null)
        }
    }
}

/**
 * Starts the computer's set-up and shows how it goes: advice for the current connection, a
 * question before mobile data is used, the installer's steps, and what to do when it stopped.
 * [onLater] adds "Later, on Wi-Fi" where the owner can move on without it.
 */
@Composable
internal fun SetUpComputer(state: ComputerState, onLater: (() -> Unit)?) {
    val graph = rememberGraph()
    val network by rememberNetwork(LocalContext.current)
    var startError by remember { mutableStateOf<String?>(null) }
    var askMobile by remember { mutableStateOf(false) }
    // Steps the installer has reported so far, so the owner sees progress, not one moving line.
    val stepsSeen = remember { mutableStateListOf<String>() }
    LaunchedEffect(state) {
        val step = (state as? ComputerState.Installing)?.step
        if (step != null && stepsSeen.lastOrNull() != step) stepsSeen += step
    }

    // The owner confirmed the set-up's size on mobile data: the data rules let exactly that
    // much through today, and the owner's own data settings stay as they are.
    fun install(onMobileData: Boolean) {
        startError = null
        if (onMobileData) ComputerSetup.setupDownloads().forEach { (kind, bytes) -> graph.dataBudget.allowOnce(kind, bytes) }
        // From the tap, while Android allows it: set-up runs on when the owner leaves the app.
        EngineService.start(graph.context)
        graph.scope.launch {
            try {
                graph.computer.install()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                startError = Redact.text(e.message ?: "Set-up stopped.").take(200)
            }
        }
    }
    val start = { if (network == Network.MOBILE) askMobile = true else install(onMobileData = false) }

    when (state) {
        ComputerState.NotInstalled -> NotInstalled(network, startError, onStart = start, onLater = onLater)
        is ComputerState.Installing -> Installing(state, stepsSeen)
        is ComputerState.Broken -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NoticeCard(title = state.why, text = state.fix, tone = Tone.ERROR)
            startError?.let { NoticeCard(it, Tone.ERROR) }
            PrimaryAction("Try again", onClick = start)
            onLater?.let { QuietAction("Later, on Wi-Fi", onClick = it) }
        }
        ComputerState.Ready, is ComputerState.Updating -> Unit
    }

    if (askMobile) {
        AlertDialog(
            onDismissRequest = { askMobile = false },
            title = { Text("Set up on mobile data?") },
            text = {
                Text(
                    "Set-up downloads $SETUP_DOWNLOAD_TEXT: the computer now, each agent the first time you open it. " +
                        "On mobile data that can cost money or use up your plan; on Wi-Fi it costs nothing.\n\n" +
                        "For this set-up only, its downloads may use mobile data today, up to " +
                        "${Formats.bytes(ComputerSetup.setupDownloads().values.sum())}. Your data settings do not change.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askMobile = false
                    install(onMobileData = true)
                }) { Text("Use mobile data") }
            },
            dismissButton = { TextButton(onClick = { askMobile = false }) { Text("Wait for Wi-Fi") } },
        )
    }
}

@Composable
private fun NotInstalled(network: Network, error: String?, onStart: () -> Unit, onLater: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (network) {
            Network.WIFI -> NoticeCard("You're on Wi-Fi: a good time to set up.", Tone.OK)
            Network.MOBILE -> NoticeCard("You're on mobile data. Wi-Fi is better for a download this size.", Tone.WARN)
            Network.OFFLINE -> NoticeCard("No internet connection. Set-up starts when you're online.", Tone.WARN)
        }
        if (error != null) NoticeCard(error, Tone.ERROR)
        PrimaryAction(
            text = if (network == Network.MOBILE) "Set up on mobile data" else "Set up now",
            enabled = network != Network.OFFLINE,
            onClick = onStart,
        )
        onLater?.let { QuietAction("Later, on Wi-Fi", onClick = it) }
    }
}

@Composable
private fun Installing(state: ComputerState.Installing, stepsSeen: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProgressCard(state.step, state.fraction, state.bytesTotal.takeIf { it > 0 }?.let { Formats.part(state.bytesDone, it) })
        if (stepsSeen.size > 1) {
            OutlinedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stepsSeen.dropLast(1).forEach { done ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = "Done", tint = toneColor(Tone.OK), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(done, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        FinePrint("You can leave this screen: set-up keeps going and continues after a break.")
    }
}

@Composable
internal fun ProgressCard(step: String, fraction: Float?, bytes: String?) {
    OutlinedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(step, style = MaterialTheme.typography.titleSmall)
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (bytes != null) Text(bytes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The current connection, re-read every few seconds (Wi-Fi comes and goes during set-up). */
@Composable
internal fun rememberNetwork(context: Context): State<Network> {
    val state = remember { mutableStateOf(readNetwork(context)) }
    LaunchedEffect(Unit) {
        while (isActive) {
            state.value = readNetwork(context)
            delay(3000)
        }
    }
    return state
}

private fun readNetwork(context: Context): Network {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return Network.OFFLINE
    val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return Network.OFFLINE
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Network.OFFLINE
    return if (manager.isActiveNetworkMetered) Network.MOBILE else Network.WIFI
}
