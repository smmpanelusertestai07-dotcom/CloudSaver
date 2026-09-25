package com.pocketide.agents

import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.sync.BackupState
import com.pocketide.sync.SessionBackup
import kotlinx.coroutines.CancellationException

/**
 * What removing an added agent must not lose. Its room's work folder holds every session
 * worktree of that agent (uncommitted changes) and their media, and its home holds chat
 * transcripts, so before the room is deleted each open session is committed and pushed and
 * every chat is uploaded, the way Reset computer saves first. Whatever is still only on this
 * phone afterwards is named, and the agent stays.
 */
internal class AgentRemoval(private val ports: Ports) {

    /** The parts of the app a removal saves through. */
    interface Ports {
        suspend fun stopRoom(agentId: String)

        fun sessions(): List<SessionRecord>

        /** Commits the session's worktree and pushes its branch; null when saved, else why not. */
        suspend fun saveNow(sessionId: String): String?

        /** Uploads these sessions' chats and media to Drive now; throws when it cannot. */
        suspend fun upload(sessionIds: List<String>)

        fun backup(sessionId: String): SessionBackup?
    }

    /**
     * Saves [agentId]'s sessions. Returns what is still only on this phone, each as the end of
     * a sentence ("\"Login fix\" has work that is not on GitHub yet"); empty when nothing is.
     */
    suspend fun saveFirst(agentId: String): List<String> {
        // Nothing may write into the worktrees or transcripts while they are saved.
        ports.stopRoom(agentId)
        val own = ports.sessions().filter { it.agentId == agentId && it.status != SessionStatus.DELETED }
        val problems = mutableListOf<String>()
        own.filterNot { it.backUp }.forEach { problems += "${quoted(it)} is kept on this phone only" }
        own.filter { it.status == SessionStatus.OPEN && it.deletedAt == null }.forEach { session ->
            // saveNow answers null once the session is pushed.
            val saved = attempt { ports.saveNow(session.id) }.fold({ it == null }, { false })
            if (!saved) problems += "${quoted(session)} has work that is not on GitHub yet"
        }
        val backedUp = own.filter { it.backUp }
        if (backedUp.isNotEmpty()) {
            if (attempt { ports.upload(backedUp.map { it.id }) }.isFailure) {
                problems += "its chats could not be backed up to Drive now"
            } else {
                backedUp.filter { notInDrive(ports.backup(it.id)) }.forEach { problems += "${quoted(it)} has parts that are not in Drive yet" }
            }
        }
        return problems.distinct()
    }

    private fun notInDrive(backup: SessionBackup?): Boolean =
        backup != null && (backup.state != BackupState.BACKED_UP || backup.pendingBytes > 0 || backup.videosWaitingForWifi > 0)

    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: Exception) {
        Result.failure(failed)
    }

    companion object {
        private const val MAX_TITLE = 24
        private const val MAX_NAME = 24

        private fun quoted(session: SessionRecord) = "\"${short(session.title, MAX_TITLE)}\""

        private fun short(text: String, max: Int) = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

        /**
         * The one plain sentence that refuses the removal, short enough for a snackbar: the
         * first thing not saved, and how many more there are.
         */
        fun refusal(name: String, problems: List<String>): String {
            val more = problems.size - 1
            val andMore = if (more > 0) " (and $more more)" else ""
            return "${short(name, MAX_NAME)} was not removed: ${problems.first()}$andMore. Save it first; nothing was deleted."
        }
    }
}
