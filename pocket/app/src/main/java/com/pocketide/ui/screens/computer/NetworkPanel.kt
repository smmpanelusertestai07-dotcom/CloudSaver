package com.pocketide.ui.screens.computer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketide.core.Ist
import com.pocketide.linux.HostCheck
import com.pocketide.linux.NetworkReport
import com.pocketide.ui.components.InfoRow
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.Tone
import com.pocketide.ui.manage.ActionRunner
import com.pocketide.ui.manage.Hint
import com.pocketide.ui.manage.ToneLine
import com.pocketide.ui.manage.Told
import com.pocketide.ui.manage.rememberGraph

private const val CHECK = "check-network"

/** Computer → Check network: every site PocketIDE needs, a lookup from inside Linux, and what blocks them. */
@Composable
fun NetworkPanel(runner: ActionRunner) {
    val graph = rememberGraph()
    var report by remember { mutableStateOf<NetworkReport?>(null) }
    val busy = runner.isBusy(CHECK)
    SectionCard("Network") {
        Hint("Checks every site PocketIDE and the agents need. The computer uses this phone's own connection.")
        OutlinedButton(
            onClick = { runner.run(CHECK, onSuccess = { r: NetworkReport -> report = r }) { graph.computer.checkNetwork() } },
            enabled = !busy,
        ) {
            Icon(Icons.Outlined.NetworkCheck, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "Checking…" else "Check network")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        report?.let { r ->
            if (r.blockers.isEmpty() && r.hosts.all { it.ok } && r.linuxDns?.ok != false) {
                ToneLine(Told("Every site answered.", Tone.OK))
            }
            r.blockers.forEach { ToneLine(Told(it, Tone.WARN)) }
            HorizontalDivider()
            Column {
                r.hosts.sortedBy { it.ok }.forEach { HostLine(it) }
                r.linuxDns?.let { HostLine(it) }
            }
            if (r.checkedAt > 0) Hint("Checked ${Ist.dateTime(r.checkedAt)}.")
        }
    }
}

@Composable
private fun HostLine(check: HostCheck) {
    InfoRow(check.purpose, if (check.ok) "Reached" else "Not reached")
    Text(
        if (check.detail.isBlank()) check.host else "${check.host} · ${check.detail}",
        style = MaterialTheme.typography.bodySmall,
        color = if (check.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
    )
}
