package com.pocketide.builds

import com.pocketide.core.NotificationIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildNoticeIdsTest {
    /**
     * Build and scheduled-task notices once used hashed ids (runId.hashCode()), which could land
     * on the engine's ongoing notification or another kind's id and replace it.
     */
    @Test
    fun buildAndTaskNoticesHaveIdsOfTheirOwn() {
        val theirs = listOf(NotificationIds.BUILD_ENDED, NotificationIds.SCHEDULED_TASK_ENDED)
        val others = setOf(
            NotificationIds.SYNC_RUNNING,
            NotificationIds.ENGINE_RUNNING,
            NotificationIds.ENGINE_STOPPED,
            NotificationIds.SCHEDULED_RUN,
            NotificationIds.ROOM,
            NotificationIds.NEW_AGENTS,
            NotificationIds.APP_UPDATE,
        )
        assertEquals(2, theirs.toSet().size)
        assertTrue(theirs.none { it in others })
        assertTrue(theirs.none { it in NotificationIds.SYNC_FIRST..NotificationIds.SYNC_LAST })
    }
}
