package com.pocketide.usage

import com.pocketide.github.AccountUsage
import com.pocketide.github.UsageLine
import com.pocketide.github.WorkflowRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildMinutesTest {
    private fun run(name: String, minutes: Int, conclusion: String? = "success", status: String = "completed") = WorkflowRun(
        id = minutes.toLong(),
        name = name,
        branch = "pocket/claude/x",
        status = status,
        conclusion = conclusion,
        createdAt = "2026-09-20T10:00:00Z",
        updatedAt = "2026-09-20T10:%02d:30Z".format(minutes),
        htmlUrl = "https://github.com/alice/demo/actions/runs/1",
    )

    private fun minutes(sku: String, quantity: Double) = UsageLine("actions", sku, quantity, "Minutes", 0.0)

    @Test
    fun plansCarryTheirIncludedMinutes() {
        assertEquals(2_000, ActionsPlans.includedMinutes("free"))
        assertEquals(3_000, ActionsPlans.includedMinutes("pro"))
        assertEquals(3_000, ActionsPlans.includedMinutes("Team"))
        assertEquals(50_000, ActionsPlans.includedMinutes("enterprise"))
        assertNull(ActionsPlans.includedMinutes(null))
        assertNull(ActionsPlans.includedMinutes("something new"))
        assertTrue(ActionsPlans.BILLING_URL.startsWith("https://docs.github.com/"))
    }

    @Test
    fun usedMinutesCountWithMultipliers() {
        val lines = listOf(
            minutes("actions_linux", 100.0),
            minutes("actions_windows", 10.0),
            minutes("actions_macos", 3.0),
            UsageLine("actions", "actions_storage", 5.0, "GigabyteHours", 0.0),
            UsageLine("copilot", "copilot_premium_request", 50.0, "Requests", 0.0),
        )
        assertEquals(100.0 + 20.0 + 30.0, BuildMinutes.countedMinutesUsed(lines), 0.001)
    }

    @Test
    fun onlySuccessfulPocketIdeBuildsAreSamples() {
        val samples = BuildMinutes.samples(
            listOf(
                run("PocketIDE Android release", 8),
                run("PocketIDE Flutter Android", 10),
                run("PocketIDE iOS simulator", 12),
                run("PocketIDE Android release", 30, conclusion = "failure"),
                run("PocketIDE Android release", 5, status = "in_progress", conclusion = null),
                run("PocketIDE Docker build", 4),
                run("CI", 3),
            ),
        )
        assertEquals(listOf(BuildSample.Family.ANDROID, BuildSample.Family.ANDROID, BuildSample.Family.IOS), samples.map { it.family })
        assertEquals(8.5, samples.first().minutes, 0.001)
    }

    @Test
    fun estimateMath() {
        val usage = AccountUsage("free", listOf(minutes("actions_linux", 380.0), minutes("actions_macos", 50.0)), null, null)
        val samples = listOf(
            BuildSample(BuildSample.Family.ANDROID, 7.2),
            BuildSample(BuildSample.Family.ANDROID, 8.8),
            BuildSample(BuildSample.Family.IOS, 11.5),
            BuildSample(BuildSample.Family.IOS, 12.5),
        )
        val estimate = BuildMinutes.estimate(usage, samples)!!
        // 2,000 − (380 + 50 × 10) = 1,120 left; Android ⌈8.0⌉ = 8 min; iOS ⌈12.0⌉ × 10 = 120.
        assertEquals(1_120, estimate.minutesLeft)
        assertEquals(8, estimate.androidMinutesEach)
        assertEquals(140, estimate.androidLeft)
        assertEquals(120, estimate.iosMinutesEach)
        assertEquals(9, estimate.iosLeft)
        assertTrue(estimate.basis.contains(ActionsPlans.AS_OF))
        assertTrue(estimate.basis.contains(ActionsPlans.BILLING_URL))
    }

    @Test
    fun noMadeUpNumbers() {
        val usage = AccountUsage("pro", emptyList(), null, null)
        assertNull("no builds yet", BuildMinutes.estimate(usage, emptyList()))
        assertNull("one build is not an average", BuildMinutes.estimate(usage, listOf(BuildSample(BuildSample.Family.ANDROID, 5.0))))
        assertNull(
            "an unknown plan has no allowance",
            BuildMinutes.estimate(AccountUsage(null, emptyList(), null, null), List(3) { BuildSample(BuildSample.Family.ANDROID, 5.0) }),
        )
        val androidOnly = BuildMinutes.estimate(usage, List(2) { BuildSample(BuildSample.Family.ANDROID, 5.0) })!!
        assertEquals(600, androidOnly.androidLeft)
        assertNull(androidOnly.iosLeft)
    }

    @Test
    fun anAllowanceUsedUpLeavesNoBuilds() {
        val usage = AccountUsage("free", listOf(minutes("actions_macos", 300.0)), null, null)
        val estimate = BuildMinutes.estimate(usage, List(2) { BuildSample(BuildSample.Family.IOS, 10.0) })!!
        assertEquals(0, estimate.minutesLeft)
        assertEquals(0, estimate.iosLeft)
    }
}
