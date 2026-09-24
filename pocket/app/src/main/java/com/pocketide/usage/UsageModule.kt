package com.pocketide.usage

import com.pocketide.AppGraph
import com.pocketide.github.AccountUsage
import com.pocketide.github.RepoUsage
import com.pocketide.google.DriveQuota

fun createUsageReporter(graph: AppGraph): UsageReporter = StubUsage().also { graph.hashCode() }

private class StubUsage : UsageReporter {
    override suspend fun github(): AccountUsage? = null
    override suspend fun repo(projectId: String): RepoUsage? = null
    override suspend fun google(): DriveQuota? = null
    override suspend fun estimate(): BuildEstimate? = null
}
