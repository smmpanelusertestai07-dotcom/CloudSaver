package com.pocketide.sessions.transcripts

import com.pocketide.core.AppDirs
import com.pocketide.model.SessionRecord
import com.pocketide.projects.SafeFiles
import com.pocketide.sessions.TranscriptEntry
import kotlinx.serialization.json.JsonObject
import java.io.File

/** How and where one agent writes its transcripts (JSON Lines for all three official agents). */
internal interface TranscriptFormat {
    val id: String

    /** What one line shows in the read-only view; nothing for lines that are not messages. */
    fun entries(line: JsonObject): List<TranscriptEntry>

    /** Adds one line to the facts of its file ([line] is null when [raw] is not a JSON object). */
    fun count(raw: String, line: JsonObject?, tally: Tally)

    /** Finds the transcripts in an agent's room [home] and says which of [sessions] each belongs to. */
    fun locate(home: File, sessions: List<SessionRecord>, source: FactsSource): Map<String, SessionTranscript>

    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val TOOL = "tool"
        const val NOTE = "note"

        /** The format of an agent's transcripts (format ids are the agents' ids), or null when unknown. */
        fun of(agentId: String): TranscriptFormat? = when (agentId) {
            ClaudeFormat.id -> ClaudeFormat
            CodexFormat.id -> CodexFormat
            AntigravityFormat.id -> AntigravityFormat
            else -> null
        }

        /** The path a room sees for a file in its home. */
        fun guestPath(home: File, file: File): String =
            AppDirs.GUEST_HOME + "/" + file.relativeTo(home).invariantSeparatorsPath
    }
}

/** Facts of transcript files, read incrementally by the index. */
internal interface FactsSource {
    /** Facts of [file] with its new lines read. */
    fun read(file: File): FileFacts

    /** Facts of a compressed [file], carried over from the [plain] file it replaced, if that was read. */
    fun carried(file: File, plain: File): FileFacts?
}

/** One transcript file and what is known about it. */
internal data class Found(val file: File, val facts: FileFacts)

/**
 * A session's transcripts as found on this phone: the [conversations] the view reads, and the
 * [roots] (files and folders) that belong to the session in the agent's home.
 */
internal class SessionTranscript(val conversations: List<Found>, val roots: List<File>) {

    /** The conversation itself, oldest file first (subagents' threads are left out). */
    val main: List<Found> = conversations.filterNot { it.facts.side }.sortedBy { it.file.lastModified() }

    val tokensIn: Long get() = conversations.sumOf { it.facts.tokensIn }
    val tokensOut: Long get() = conversations.sumOf { it.facts.tokensOut }
    val newestAt: Long? get() = conversations.mapNotNull { it.facts.newestAt }.maxOrNull()
    val firstUserText: String? get() = main.firstNotNullOfOrNull { it.facts.firstUserText }

    /** The agent's own reference of the conversation it writes now, to continue it. */
    val ref: String? get() = main.lastOrNull()?.facts?.ref

    /** Size of the file the agent writes now: past about 10 MB an agent may fail to resume it. */
    val currentBytes: Long get() = main.lastOrNull()?.file?.length() ?: 0

    fun bytes(): Long = roots.sumOf { SafeFiles.size(it) }

    fun files(): List<File> = roots.flatMap { root ->
        if (SafeFiles.isDirectory(root)) SafeFiles.files(root) else listOf(root).filter(SafeFiles::isFile)
    }
}
