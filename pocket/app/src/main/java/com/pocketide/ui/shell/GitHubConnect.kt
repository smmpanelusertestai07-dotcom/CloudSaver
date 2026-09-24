package com.pocketide.ui.shell

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketide.core.Redact
import com.pocketide.github.DeviceCode
import com.pocketide.github.DevicePoll
import com.pocketide.github.GitHubAccount
import com.pocketide.github.GitHubAuth
import com.pocketide.github.NotConnectedException
import com.pocketide.ui.components.SelectableText
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private sealed interface DeviceFlow {
    data object Idle : DeviceFlow
    data object Starting : DeviceFlow
    data class Waiting(val code: DeviceCode) : DeviceFlow
    data object Denied : DeviceFlow
    data object Expired : DeviceFlow
    data class Failed(val why: String) : DeviceFlow
    /** Connected: the caller shows the account from here on. */
    data object Done : DeviceFlow
}

/**
 * GitHub's device-code sign-in: show the code, open GitHub in Chrome, poll at the pace GitHub
 * sets until the owner approves, denies, or the code expires. Used by set-up and by the
 * "GitHub disconnected" lock.
 *
 * The flow lives in plain `remember`, not saved state, on purpose: the device code must not be
 * written into the saved-instance bundle. Rotation does not recreate the activity (it handles
 * configuration changes itself), and after a process death the owner simply gets a new code.
 */
@Composable
fun GitHubConnectPanel(
    auth: GitHubAuth,
    openUrl: (String) -> Unit,
    onConnected: (GitHubAccount) -> Unit,
    startLabel: String = "Connect GitHub",
) {
    val scope = rememberCoroutineScope()
    var flow by remember { mutableStateOf<DeviceFlow>(DeviceFlow.Idle) }
    var offline by remember { mutableStateOf(false) }

    fun start() {
        flow = DeviceFlow.Starting
        offline = false
        scope.launch {
            flow = try {
                DeviceFlow.Waiting(auth.startDeviceFlow())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DeviceFlow.Failed(explain(e))
            }
        }
    }

    val waiting = flow as? DeviceFlow.Waiting
    LaunchedEffect(waiting?.code) {
        val code = waiting?.code ?: return@LaunchedEffect
        var pause = DeviceFlowTiming.pollDelayMs(code.intervalSeconds)
        while (isActive) {
            delay(pause)
            if (DeviceFlowTiming.expired(code.expiresAtMs, System.currentTimeMillis())) {
                flow = DeviceFlow.Expired
                break
            }
            val result = try {
                auth.poll(code)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A dropped connection is not an answer: keep the code and keep asking.
                offline = true
                continue
            }
            offline = false
            when (result) {
                DevicePoll.Pending -> Unit
                is DevicePoll.SlowDown -> pause = DeviceFlowTiming.pollDelayMs(result.intervalSeconds)
                is DevicePoll.Connected -> {
                    flow = DeviceFlow.Done
                    onConnected(result.account)
                    break
                }
                DevicePoll.Denied -> {
                    flow = DeviceFlow.Denied
                    break
                }
                DevicePoll.Expired -> {
                    flow = DeviceFlow.Expired
                    break
                }
                is DevicePoll.Failed -> {
                    flow = DeviceFlow.Failed(Redact.text(result.why))
                    break
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val current = flow) {
            DeviceFlow.Idle -> PrimaryAction(startLabel, onClick = ::start)
            DeviceFlow.Starting -> PrimaryAction("Getting a code…", onClick = {}, busy = true)
            is DeviceFlow.Waiting -> WaitingForApproval(current.code, offline, openUrl)
            DeviceFlow.Denied -> Retry("You pressed Cancel on GitHub. Nothing was connected.", ::start)
            DeviceFlow.Expired -> Retry("The code expired before it was used. Get a new one.", ::start)
            is DeviceFlow.Failed -> Retry(current.why, ::start)
            DeviceFlow.Done -> Unit
        }
    }
}

@Composable
private fun WaitingForApproval(code: DeviceCode, offline: Boolean, openUrl: (String) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(code) {
        while (isActive) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    OutlinedCard {
        Column(Modifier.padding(20.dp)) {
            Text("Your one-time code", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectableText(
                text = codeText(code.userCode),
                sizeSp = 34f,
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .semantics { contentDescription = "Code ${code.userCode.toList().joinToString(" ")}" },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(
                    if (offline) "No connection, still trying" else "Waiting for you on GitHub",
                    if (offline) Tone.WARN else Tone.NEUTRAL,
                )
                Text(
                    "Expires in ${Formats.countdown(code.expiresAtMs - now)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Text(
        "Open GitHub, sign in if asked, and type this code. Long-press the code to copy it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PrimaryAction("Open GitHub", onClick = { openUrl(code.verificationUri) })
    FinePrint("Official GitHub sign-in. PocketIDE never sees your password.")
}

@Composable
private fun Retry(message: String, onRetry: () -> Unit) {
    NoticeCard(message, Tone.WARN)
    PrimaryAction("Try again", onClick = onRetry)
}

/** The signed-in account, as set-up and Settings show it. */
@Composable
fun GitHubAccountCard(account: GitHubAccount) {
    OutlinedCard {
        Row(
            Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GitHubAvatar(account.login, account.avatarUrl, size = 52.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(account.name ?: account.login, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (account.name != null) {
                    Text("@${account.login}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            StatusChip("Connected", Tone.OK)
        }
    }
}

private fun codeText(userCode: String): CharSequence = SpannableString(userCode).apply {
    setSpan(TypefaceSpan("monospace"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

private fun explain(e: Exception): String = when (e) {
    is NotConnectedException -> Redact.text(e.message ?: "GitHub sign-in is not available.")
    is IOException -> "No connection to GitHub. Check the internet and try again."
    else -> "GitHub sign-in could not start. Try again in a minute."
}
