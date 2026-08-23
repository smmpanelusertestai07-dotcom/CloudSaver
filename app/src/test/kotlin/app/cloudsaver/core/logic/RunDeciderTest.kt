package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunDeciderTest {

    private val fresh = RunDecider.Budget(0, 0)

    private fun power(
        plugged: Boolean = false,
        pct: Int = 80,
        temp: Int = 300,
        saver: Boolean = false,
        interactive: Boolean = false,
        screenOffMs: Long = 10 * 60_000,
        thermal: Boolean = false
    ) = RunDecider.Power(plugged, pct, temp, saver, interactive, screenOffMs, thermal)

    // ---- charging ----------------------------------------------------------

    @Test
    fun chargingRunsEverythingEvenWithScreenOn() {
        val p = RunDecider.decide(
            SpeedMode.SMART,
            power(plugged = true, pct = 50, interactive = true, screenOffMs = 0),
            fresh
        )
        assertTrue(p.photos)
        assertTrue(p.videos)
        assertEquals(RunDecider.Wait.NONE, p.wait)
    }

    @Test
    fun chargingIgnoresTheDailyBudget() {
        val spent = RunDecider.Budget(videoEncodeMs = 10 * 60 * 60_000, photosOnBattery = 5_000)
        val p = RunDecider.decide(SpeedMode.SMART, power(plugged = true), spent)
        assertTrue(p.photos)
        assertTrue(p.videos)
    }

    @Test
    fun chargingBelowFifteenPercentDoesPhotosOnly() {
        val p = RunDecider.decide(SpeedMode.SMART, power(plugged = true, pct = 9), fresh)
        assertTrue(p.photos)
        assertFalse(p.videos)
        assertEquals(RunDecider.Wait.NONE, p.wait)
    }

    @Test
    fun chargingAtExactlyFifteenPercentDoesVideos() {
        val p = RunDecider.decide(SpeedMode.SMART, power(plugged = true, pct = 15), fresh)
        assertTrue(p.videos)
    }

    // ---- battery saver / heat (all modes) ----------------------------------

    @Test
    fun batterySaverPausesEveryMode() {
        for (mode in SpeedMode.entries) {
            val p = RunDecider.decide(mode, power(plugged = true, saver = true), fresh)
            assertFalse("$mode should pause", p.canRun)
            assertEquals(RunDecider.Wait.BATTERY_SAVER, p.wait)
        }
    }

    @Test
    fun thermalOrHotBatteryStops() {
        val thermal = RunDecider.decide(SpeedMode.SMART, power(plugged = true, thermal = true), fresh)
        assertEquals(RunDecider.Wait.TOO_HOT, thermal.wait)
        val hot = RunDecider.decide(SpeedMode.SMART, power(plugged = true, temp = 430), fresh)
        assertEquals(RunDecider.Wait.TOO_HOT, hot.wait)
        assertFalse(hot.canRun)
    }

    @Test
    fun appPauseWinsOverEverything() {
        val p = RunDecider.decide(SpeedMode.SMART, power(plugged = true), fresh, paused = true)
        assertFalse(p.canRun)
        assertEquals(RunDecider.Wait.PAUSED, p.wait)
    }

    // ---- charging only mode -------------------------------------------------

    @Test
    fun chargingOnlyWaitsForTheCharger() {
        val p = RunDecider.decide(SpeedMode.CHARGING_ONLY, power(pct = 95), fresh)
        assertFalse(p.canRun)
        assertEquals(RunDecider.Wait.NOT_CHARGING, p.wait)
    }

    @Test
    fun chargingOnlyRunsWhenPlugged() {
        val p = RunDecider.decide(
            SpeedMode.CHARGING_ONLY,
            power(plugged = true, pct = 40, interactive = true),
            fresh
        )
        assertTrue(p.photos)
        assertTrue(p.videos)
    }

    // ---- SMART on battery ---------------------------------------------------

    @Test
    fun smartOnBatteryNeedsThirtyPercent() {
        val low = RunDecider.decide(SpeedMode.SMART, power(pct = 29), fresh)
        assertFalse(low.canRun)
        assertEquals(RunDecider.Wait.BATTERY_LOW, low.wait)
        assertEquals(30, low.floorPct)

        val ok = RunDecider.decide(SpeedMode.SMART, power(pct = 30), fresh)
        assertTrue(ok.videos)
    }

    @Test
    fun smartOnBatteryWaitsForScreenOffTwoMinutes() {
        val tooSoon = RunDecider.decide(SpeedMode.SMART, power(screenOffMs = 60_000), fresh)
        // Photos may still run, videos may not.
        assertTrue(tooSoon.photos)
        assertFalse(tooSoon.videos)

        val ready = RunDecider.decide(SpeedMode.SMART, power(screenOffMs = 120_000), fresh)
        assertTrue(ready.videos)
    }

    @Test
    fun smartPhotosRunWithScreenOnButVideosDoNot() {
        val p = RunDecider.decide(
            SpeedMode.SMART,
            power(interactive = true, screenOffMs = 0),
            fresh
        )
        assertTrue(p.photos)
        assertFalse(p.videos)
        assertEquals(RunDecider.Wait.NONE, p.wait)
    }

    @Test
    fun smartVideoBudgetIsThirtyMinutes() {
        val almost = RunDecider.Budget(videoEncodeMs = 29 * 60_000, photosOnBattery = 0)
        assertTrue(RunDecider.decide(SpeedMode.SMART, power(), almost).videos)

        val used = RunDecider.Budget(videoEncodeMs = 30 * 60_000, photosOnBattery = 0)
        val p = RunDecider.decide(SpeedMode.SMART, power(), used)
        assertFalse(p.videos)
        assertTrue(p.photos) // photos are exempt from the budget
    }

    @Test
    fun budgetExhaustedAndPhotoCapReachedReportsBudget() {
        val used = RunDecider.Budget(videoEncodeMs = 30 * 60_000, photosOnBattery = 200)
        val p = RunDecider.decide(SpeedMode.SMART, power(), used)
        assertFalse(p.canRun)
        assertEquals(RunDecider.Wait.BUDGET_USED, p.wait)
    }

    @Test
    fun photoCapAloneIsReportedWhenVideosCanStillNotRunForNoOtherReason() {
        // Screen on (videos blocked by screen) + photo cap reached.
        val capped = RunDecider.Budget(videoEncodeMs = 0, photosOnBattery = 200)
        val p = RunDecider.decide(SpeedMode.SMART, power(interactive = true), capped)
        assertFalse(p.canRun)
        assertEquals(RunDecider.Wait.SCREEN_ON, p.wait)
    }

    @Test
    fun photoCapOnlyLimitsPhotos() {
        val capped = RunDecider.Budget(videoEncodeMs = 0, photosOnBattery = 200)
        val p = RunDecider.decide(SpeedMode.SMART, power(), capped)
        assertFalse(p.photos)
        assertTrue(p.videos)
    }

    // ---- FAST ----------------------------------------------------------------

    @Test
    fun fastHasLowerFloorNoScreenWaitAndBiggerBudget() {
        val p = RunDecider.decide(
            SpeedMode.FAST,
            power(pct = 25, interactive = true, screenOffMs = 0),
            RunDecider.Budget(videoEncodeMs = 45 * 60_000, photosOnBattery = 0)
        )
        assertTrue(p.videos) // screen-on allowed, 45 min < 60 min budget
        assertEquals(25, p.floorPct)

        val out = RunDecider.decide(
            SpeedMode.FAST,
            power(pct = 24),
            RunDecider.Budget(0, 0)
        )
        assertEquals(RunDecider.Wait.BATTERY_LOW, out.wait)

        val spent = RunDecider.decide(
            SpeedMode.FAST,
            power(),
            RunDecider.Budget(videoEncodeMs = 60 * 60_000, photosOnBattery = 0)
        )
        assertFalse(spent.videos)
    }

    // ---- manual "Run now" ----------------------------------------------------

    @Test
    fun manualRunIgnoresModeBudgetAndScreen() {
        val p = RunDecider.decideManual(
            power(pct = 20, interactive = true, screenOffMs = 0)
        )
        assertTrue(p.photos)
        assertTrue(p.videos)
    }

    @Test
    fun manualRunStillRespectsHardLimits() {
        val low = RunDecider.decideManual(power(pct = 14))
        assertFalse(low.canRun)
        assertEquals(RunDecider.Wait.BATTERY_LOW, low.wait)

        val hot = RunDecider.decideManual(power(plugged = true, temp = 500))
        assertFalse(hot.canRun)
        assertEquals(RunDecider.Wait.TOO_HOT, hot.wait)

        // Plugged in at 5% is still fine for a user-initiated run.
        assertTrue(RunDecider.decideManual(power(plugged = true, pct = 5)).canRun)
    }

    // ---- table -----------------------------------------------------------------

    @Test
    fun modeParameterTable() {
        assertEquals(30, RunDecider.batteryFloor(SpeedMode.SMART))
        assertEquals(25, RunDecider.batteryFloor(SpeedMode.FAST))
        assertEquals(15, RunDecider.batteryFloor(SpeedMode.CHARGING_ONLY))
        assertEquals(30 * 60_000L, RunDecider.videoBudgetMs(SpeedMode.SMART))
        assertEquals(60 * 60_000L, RunDecider.videoBudgetMs(SpeedMode.FAST))
        assertEquals(0L, RunDecider.videoBudgetMs(SpeedMode.CHARGING_ONLY))
        assertEquals(2 * 60_000L, RunDecider.screenOffWaitMs(SpeedMode.SMART))
        assertEquals(0L, RunDecider.screenOffWaitMs(SpeedMode.FAST))
    }
}
