package com.pocketide.usage

import com.pocketide.github.AccountUsage
import com.pocketide.github.RepoUsage
import com.pocketide.google.DriveQuota

/** "About N Android / iOS builds left this month", from the owner's own averages. */
data class BuildEstimate(val androidLeft: Int?, val iosLeft: Int?, val basis: String)

/** Live usage for the Usage screen: never frozen numbers. */
interface UsageReporter {
    suspend fun github(): AccountUsage?
    suspend fun repo(projectId: String): RepoUsage?
    suspend fun google(): DriveQuota?
    suspend fun estimate(): BuildEstimate?
}
