package com.pocketide.sync

import com.pocketide.core.NotificationIds
import com.pocketide.limiter.EngineNotices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeIdsTest {
    private val clock = FakeClock()

    /** Every kind of notice the sync engine posts. */
    private fun postedKeys(): List<String> {
        val phone = TestPhone(FakeAccounts(clock), clock)
        val kit = SyncKit(phone)
        val run = Run(kit, phone.cipher)
        val notices = Notices(kit)
        notices.leaseLost(run, "Other phone")
        notices.storageFull(run, googleFull = true)
        notices.storageFull(run, googleFull = false)
        notices.retention(run, 2, clock.now, trim = false)
        notices.retention(run, 2, clock.now, trim = true)
        notices.phoneNearlyFull(run, 8L shl 30, 10L shl 30)
        notices.phoneCleaned(run, 1L shl 30)
        notices.computerNotice(run, 30, clock.now)
        notices.driveRevoked(run)
        return phone.notifier.posted.map { it.key }.distinct()
    }

    @Test
    fun eachKindOfSyncNoticeHasItsOwnIdAndNeverTheEnginesOrAnotherModules() {
        val keys = postedKeys()
        assertEquals(9, keys.size)
        assertTrue("every kind is known: ${keys - SyncNoticeIds.KEYS.toSet()}", SyncNoticeIds.KEYS.containsAll(keys))

        val ids = keys.map(SyncNoticeIds::of)
        assertEquals("one id per kind", ids.size, ids.toSet().size)
        assertTrue(ids.all { it in NotificationIds.SYNC_FIRST..NotificationIds.SYNC_LAST })
        assertTrue(SyncNoticeIds.of("a kind not listed") in NotificationIds.SYNC_FIRST..NotificationIds.SYNC_LAST)

        val others = setOf(
            NotificationIds.SYNC_RUNNING,
            EngineNotices.RUNNING_ID,
            NotificationIds.ENGINE_STOPPED,
            NotificationIds.SCHEDULED_RUN,
            NotificationIds.NEW_AGENTS,
            NotificationIds.APP_UPDATE,
        )
        assertEquals("the fixed ids differ", 6, others.size)
        assertTrue("no sync notice replaces the engine's ongoing notification", ids.none { it in others })
    }
}
