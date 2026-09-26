package com.pocketide.usage

import com.pocketide.AppGraph
import com.pocketide.core.Clock
import com.pocketide.github.GitHubApi
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

fun createUsageReporter(graph: AppGraph): UsageReporter = LiveUsage(gitHub = { graph.gitHub }, clock = graph.clock)

/**
 * Asks GitHub each time: the screen shows live numbers with the time they were read. GitHub's
 * billing report answers only with the App's "Plan: read" permission; without it the screen
 * points to GitHub's billing page instead.
 */
internal class LiveUsage(private val gitHub: () -> GitHubApi, private val clock: Clock) : UsageReporter {
    override suspend fun read(): CloudUsage {
        val report = gitHub().accountUsage()
        val now = clock.now()
        return CloudUsage.from(report, YearMonth.from(Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC)), now)
    }
}
