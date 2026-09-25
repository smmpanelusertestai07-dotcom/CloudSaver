package com.pocketide.agents

import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.sync.BackupState
import com.pocketide.sync.SessionBackup
import com.pocketide.ui.manage.PlainError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AgentRemovalTest {

    private class Ports : AgentRemoval.Ports {
        val events = mutableListOf<String>()
        var sessions = emptyList<SessionRecord>()
        val notSaved = mutableMapOf<String, String>()
        val throwsOnSave = mutableSetOf<String>()
        var uploadFails = false
        val backups = mutableMapOf<String, SessionBackup>()

        override suspend fun stopRoom(agentId: String) {
            events += "stop $agentId"
        }

        override fun sessions() = sessions

        override suspend fun saveNow(sessionId: String): String? {
            events += "save $sessionId"
            if (sessionId in throwsOnSave) throw IOException("offline")
            return notSaved[sessionId]
        }

        override suspend fun upload(sessionIds: List<String>) {
            events += "upload ${sessionIds.joinToString(",")}"
            if (uploadFails) throw IOException("Drive is not reachable.")
        }

        override fun backup(sessionId: String) = backups[sessionId]
    }

    private val ports = Ports()
    private val removal = AgentRemoval(ports)

    private fun session(
        id: String,
        agentId: String = AGENT,
        status: SessionStatus = SessionStatus.OPEN,
        backUp: Boolean = true,
        title: String = "Chat $id",
    ) = SessionRecord(
        id = id, agentId = agentId, projectId = "octo/app", title = title, branch = "pocket/$agentId/2026-09-25-$id",
        startedAt = 1L, lastActivityAt = 2L, status = status, backUp = backUp, deviceId = "phone",
    )

    @Test
    fun `a removal stops the room, pushes each open session and uploads every chat first`() = runBlocking {
        ports.sessions = listOf(
            session("s1"),
            session("s2", status = SessionStatus.ON_MAIN),
            session("s3", status = SessionStatus.DELETED),
            session("other", agentId = "codex"),
        )

        val problems = removal.saveFirst(AGENT)

        assertEquals(emptyList<String>(), problems)
        assertEquals(listOf("stop $AGENT", "save s1", "upload s1,s2"), ports.events)
    }

    @Test
    fun `uncommitted or unpushed work keeps the agent`() = runBlocking {
        ports.sessions = listOf(session("s1", title = "Login fix"), session("s2"))
        ports.notSaved["s1"] = "The check-post stopped the push."
        ports.throwsOnSave += "s2"

        val problems = removal.saveFirst(AGENT)

        assertEquals(
            listOf("\"Login fix\" has work that is not on GitHub yet", "\"Chat s2\" has work that is not on GitHub yet"),
            problems,
        )
    }

    @Test
    fun `chats not yet in Drive keep the agent`() = runBlocking {
        ports.sessions = listOf(session("s1"), session("s2"), session("s3"))
        ports.backups["s1"] = SessionBackup(BackupState.BACKED_UP)
        ports.backups["s2"] = SessionBackup(BackupState.WAITING_FOR_WIFI, videosWaitingForWifi = 1)
        ports.backups["s3"] = SessionBackup(BackupState.WAITING, pendingBytes = 4_096)

        val problems = removal.saveFirst(AGENT)

        assertEquals(
            listOf("\"Chat s2\" has parts that are not in Drive yet", "\"Chat s3\" has parts that are not in Drive yet"),
            problems,
        )
    }

    @Test
    fun `a failed upload or a chat kept on this phone only keeps the agent`() = runBlocking {
        ports.sessions = listOf(session("s1"), session("s2", backUp = false, title = "Private notes"))
        ports.uploadFails = true

        val problems = removal.saveFirst(AGENT)

        assertEquals(
            listOf("\"Private notes\" is kept on this phone only", "its chats could not be backed up to Drive now"),
            problems,
        )
        assertTrue(ports.events.contains("upload s1"))
    }

    @Test
    fun `the refusal is one plain sentence the owner is shown in full`() {
        val longTitle = "A".repeat(200)
        val longName = "Some agent with a very long name from the registry"
        ports.sessions = listOf(session("s1", title = longTitle))
        ports.notSaved["s1"] = "Not pushed."
        val problems = runBlocking { removal.saveFirst(AGENT) } + List(11) { "\"x\" has parts that are not in Drive yet" }

        val sentence = AgentRemoval.refusal(longName, problems)

        assertEquals(sentence, PlainError.readable(sentence))
        assertTrue(sentence, sentence.contains("(and 11 more)"))
        assertEquals(
            "Cline was not removed: \"Login fix\" is kept on this phone only. Save it first; nothing was deleted.",
            AgentRemoval.refusal("Cline", listOf("\"Login fix\" is kept on this phone only")),
        )
    }

    private companion object {
        const val AGENT = "cline.cline"
    }
}
