package app.cloudsaver.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How often one problem may come back as a notification when it is ignored.
 *
 * Once a day for as long as the problem lasted was the old rule, and a
 * daily notice about a cloud app that stopped uploading months ago is how
 * an app gets every notification switched off - the alert that matters
 * included.
 */
class AlertCadenceTest {

    private val day = Notifications.DEDUP_MS
    private val now = 400 * day

    @Test
    fun `an ignored alert waits a day, three days, a week, then a month`() {
        assertEquals(day, Notifications.waitAfter(1))
        assertEquals(3 * day, Notifications.waitAfter(2))
        assertEquals(7 * day, Notifications.waitAfter(3))
        assertEquals(30 * day, Notifications.waitAfter(4))
        assertEquals(30 * day, Notifications.waitAfter(40))
    }

    @Test
    fun `due follows the count`() {
        assertTrue("never posted", Notifications.due(null, now))
        assertFalse(Notifications.due(Notifications.Posted(now - day + 1, 1), now))
        assertTrue(Notifications.due(Notifications.Posted(now - day, 1), now))
        assertFalse(Notifications.due(Notifications.Posted(now - 2 * day, 2), now))
        assertTrue(Notifications.due(Notifications.Posted(now - 3 * day, 2), now))
        assertFalse(Notifications.due(Notifications.Posted(now - 20 * day, 4), now))
    }

    @Test
    fun `the record round-trips, reads old lines, and forgets a quiet month`() {
        val record = mapOf(
            "safety" to Notifications.Posted(now - day, 3),
            "stale" to Notifications.Posted(now - 31 * day, 2)
        )
        val encoded = Notifications.encodeAlertTimes(record, now)
        val back = Notifications.lastAlertTimes(encoded)
        assertEquals(Notifications.Posted(now - day, 3), back["safety"])
        assertFalse("a month of quiet starts the count again", back.containsKey("stale"))
        // Written by the build before the count was kept: posted once.
        assertEquals(
            Notifications.Posted(12345L, 1),
            Notifications.lastAlertTimes("12345 Cloud not uploading")["Cloud not uploading"]
        )
        // Damaged lines are dropped, not trusted.
        assertTrue(Notifications.lastAlertTimes("garbage\nx12 key").isEmpty())
    }

    @Test
    fun `a reminder counts only when it was shown, and mute takes down only alerts`() {
        val main = File("src/main/kotlin/app/cloudsaver")
        val notifications = File(main, "util/Notifications.kt").readText()
        assertTrue(notifications.contains("): Boolean {\n        if (!options.warningsNotif) return false"))
        val engine = File(main, "engine/MaintainEngine.kt").readText()
        assertTrue(engine.contains("if (shown) {\n                    repo.setInt(OptionsRepo.K.STALL_ALERTS"))
        assertTrue(engine.contains("if (shown) repo.setBool(OptionsRepo.K.AGED_WARNED, true)"))
        // Two different problems sharing a slot replaced each other.
        assertEquals(
            "every alert slot is its own",
            Notifications.ALERT_IDS.size,
            Notifications.ALERT_IDS.toSet().size
        )
        assertTrue(engine.contains("context, Notifications.ID_WARN_CLOUD,"))
        val actions = File(main, "util/AlertActions.kt").readText()
        assertFalse("muting must not take down the working note", actions.contains("cancelAll()"))
        assertTrue(actions.contains("Notifications.clearAlerts(app)"))
        // And a running mute is visible where alerts are switched, with a way out.
        val options = File(main, "ui/screens/OptionsScreen.kt").readText()
        assertTrue(options.contains("o.alertsMutedUntil > System.currentTimeMillis()"))
        assertTrue(options.contains("vm.unmuteAlerts()"))
    }
}
