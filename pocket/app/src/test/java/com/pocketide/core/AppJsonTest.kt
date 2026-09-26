package com.pocketide.core

import com.pocketide.model.SessionRecord
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

class AppJsonTest {
    @Test
    fun `a chat record saved with a field the app no longer has still loads`() {
        // Earlier 3.0.0 builds wrote pendingVideos; sessions.json and the vault index both read through AppJson.
        val saved = """
            [{"id":"s1","agentId":"claude","projectId":"me/app","title":"Login fix","branch":"pocket/claude/2026-09-24-login",
              "startedAt":1,"lastActivityAt":2,"pendingBytes":0,"pendingVideos":2,"deviceId":"phone"}]
        """.trimIndent()
        val record = AppJson.decodeFromString(ListSerializer(SessionRecord.serializer()), saved).single()
        assertEquals("Login fix", record.title)
        assertEquals(2L, record.lastActivityAt)
    }
}
