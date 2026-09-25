package com.pocketide.ui.screens.computer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pocketide.core.Http
import com.pocketide.core.Ist
import com.pocketide.ui.components.InfoRow
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.manage.ActionRunner
import com.pocketide.ui.manage.Hint
import com.pocketide.ui.manage.HostResult
import com.pocketide.ui.manage.NetworkCheck
import com.pocketide.ui.manage.NetworkFacts
import com.pocketide.ui.manage.ToneLine
import com.pocketide.ui.manage.Told

private const val CHECK = "check-network"

private data class CheckReport(val at: Long, val told: List<Told>, val results: List<HostResult>)

/** Computer → Check network: every site PocketIDE needs, and what blocks the ones that fail. */
@Composable
fun NetworkPanel(runner: ActionRunner, now: () -> Long) {
    val context = LocalContext.current.applicationContext
    var report by remember { mutableStateOf<CheckReport?>(null) }
    val busy = runner.isBusy(CHECK)
    SectionCard("Network") {
        Hint("Checks every site PocketIDE and the agents need. The computer uses this phone's own connection.")
        OutlinedButton(
            onClick = {
                runner.run(CHECK, onSuccess = { r: CheckReport -> report = r }) {
                    val results = NetworkCheck.run(Http.client)
                    CheckReport(now(), NetworkCheck.blockers(readNetworkFacts(context), results), results)
                }
            },
            enabled = !busy,
        ) {
            Icon(Icons.Outlined.NetworkCheck, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "Checking…" else "Check network")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        report?.let { r ->
            r.told.forEach { ToneLine(it) }
            HorizontalDivider()
            Column {
                r.results.sortedBy { it.ok }.forEach { result ->
                    InfoRow(result.host.forWhat, NetworkCheck.describe(result))
                    Text(
                        result.host.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Hint("Checked ${Ist.dateTime(r.at)}.")
        }
    }
}

/** What Android says about the network now; everything used here exists on Android 10. */
private fun readNetworkFacts(context: Context): NetworkFacts {
    val manager = context.getSystemService(ConnectivityManager::class.java)
        ?: return NetworkFacts(connected = true, validated = true, captivePortal = false, vpn = false, privateDnsServer = null, dataSaver = false)
    val network = manager.activeNetwork
    val caps = network?.let { manager.getNetworkCapabilities(it) }
    val link = network?.let { manager.getLinkProperties(it) }
    return NetworkFacts(
        connected = caps != null,
        validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        captivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
        vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
        privateDnsServer = link?.takeIf { it.isPrivateDnsActive }?.privateDnsServerName,
        dataSaver = manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
    )
}
