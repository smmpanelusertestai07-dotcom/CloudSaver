package com.pocketide.ui.screens.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketide.core.Ist
import com.pocketide.graph
import com.pocketide.ui.components.Formats
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.SecondaryAction
import com.pocketide.ui.web.Browser
import com.pocketide.usage.Allowance
import com.pocketide.usage.CloudUsage
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** This month's hours, storage and build minutes, read live from GitHub, with a daily chart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(onHelp: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    var usage by remember { mutableStateOf<CloudUsage?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reads by remember { mutableIntStateOf(0) }
    LaunchedEffect(reads) {
        loading = true
        try {
            usage = graph.usage.read()
            problem = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            problem = e.message ?: "GitHub did not answer. Pull down to try again."
        }
        loading = false
    }
    PullToRefreshBox(isRefreshing = loading, onRefresh = { reads++ }, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("Usage", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        usage?.let { "This month on GitHub, read ${Ist.dateTime(it.readAtMs)}" } ?: "This month on GitHub",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onHelp) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "About usage") }
            }
            problem?.let { NoticeCard(it, Tone.ERROR) }
            usage?.let { UsageCards(it, graph.clock.now()) }
            SecondaryAction("Open GitHub billing", onClick = { Browser.open(context, BILLING_URL) })
            SecondaryAction("Plans and upgrade", onClick = { Browser.open(context, PLANS_URL) })
            Text(
                "Free allowance as GitHub published it on ${Allowance.CHECKED_ON}. GitHub's own page is the final word.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UsageCards(usage: CloudUsage, now: Long) {
    usage.unavailableReason?.let {
        NoticeCard(it, Tone.WARN)
        return
    }
    val allowance = usage.allowance
    val today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
    SectionCard("Cloud computers (Codespaces)") {
        if (allowance != null) {
            Meter(usage.coreHoursUsed, allowance.coreHours.toDouble())
            Text(
                "${Formats.amount(usage.coreHoursUsed)} of ${allowance.coreHours} free core-hours used",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "About ${Formats.amount(usage.hoursLeftOnTwoCores ?: 0.0)} hours left on a 2-core computer. A 4-core one uses them twice as fast.",
                style = MaterialTheme.typography.bodyMedium,
            )
            val projected = usage.projectedCoreHours(today)
            val tone = if (projected > allowance.coreHours) Tone.WARN else Tone.OK
            Text(
                "At this month's pace: about ${Formats.amount(projected)} core-hours by the end of the month.",
                style = MaterialTheme.typography.bodyMedium,
                color = toneColor(tone),
            )
        } else {
            Text("${Formats.amount(usage.coreHoursUsed)} core-hours used this month.", style = MaterialTheme.typography.titleMedium)
        }
        DailyChart(usage.coreHoursByDay, today)
    }
    SectionCard("Storage") {
        if (allowance != null) Meter(usage.storageGbMonths, allowance.storageGbMonths.toDouble())
        Text(
            "${Formats.amount(usage.storageGbMonths)} GB-months" + (allowance?.let { " of ${it.storageGbMonths} free" } ?: ""),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Stopped computers still use storage until GitHub deletes them. GitHub's own image is not counted.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    SectionCard("Builds (GitHub Actions)") {
        Text("${Formats.amount(usage.actionsMinutes)} minutes this month", style = MaterialTheme.typography.titleMedium)
        Text(
            "Builds of public repositories are free. Private ones use the free minutes" +
                (allowance?.let { " (${it.actionsMinutes} a month)" } ?: "") + "; Windows counts twice, macOS ten times.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    SectionCard("Charged so far") {
        Text(Formats.usd(usage.billedUsd), style = MaterialTheme.typography.titleMedium)
        Text(
            "Without a payment method or spending limit, GitHub stops at the free allowance instead of charging.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Meter(used: Double, of: Double) {
    val share = if (of <= 0) 0f else (used / of).toFloat().coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { share },
        color = toneColor(if (share >= WARN_SHARE) Tone.WARN else Tone.OK),
        modifier = Modifier.fillMaxWidth().height(8.dp),
    )
}

/** Core-hours per day this month; today's bar in the accent colour. */
@Composable
private fun DailyChart(byDay: List<Double>, today: LocalDate) {
    val top = byDay.maxOrNull()?.takeIf { it > 0 } ?: return
    val bar = MaterialTheme.colorScheme.secondary
    val current = MaterialTheme.colorScheme.primary
    val description = "Core-hours per day this month, up to ${Formats.amount(top)} on the busiest day"
    Canvas(Modifier.fillMaxWidth().height(96.dp).padding(top = 8.dp).semantics { contentDescription = description }) {
        val slot = size.width / byDay.size
        val width = slot * BAR_SHARE
        byDay.forEachIndexed { index, value ->
            val height = (value / top).toFloat() * size.height
            drawRoundRect(
                color = if (index == today.dayOfMonth - 1) current else bar,
                topLeft = Offset(index * slot + (slot - width) / 2, size.height - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(width / 3, width / 3),
            )
        }
    }
}

private const val BILLING_URL = "https://github.com/settings/billing/usage"
private const val PLANS_URL = "https://github.com/settings/billing"
private const val WARN_SHARE = 0.8f
private const val BAR_SHARE = 0.7f
