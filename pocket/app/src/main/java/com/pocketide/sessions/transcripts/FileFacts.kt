package com.pocketide.sessions.transcripts

import kotlinx.serialization.Serializable

/**
 * What is known about one transcript file, kept between app starts so a long transcript is read
 * once and afterwards only its new lines (agents append to their transcripts).
 */
@Serializable
internal data class FileFacts(
    val path: String,
    val format: String,
    /** Bytes read so far: the end of the last complete line. */
    val offset: Long = 0,
    /** Hex of the file's first bytes when it was first read; a different start means a rewrite. */
    val head: String = "",
    val newestAt: Long? = null,
    val firstUserText: String? = null,
    /** The agent's own id for this conversation (Claude session id, Codex thread id). */
    val ref: String? = null,
    /** Codex: the directory the conversation started in. */
    val cwd: String? = null,
    /** Codex: a subagent's thread rather than the conversation itself. */
    val side: Boolean = false,
    /** Antigravity: how often each session worktree path appears. */
    val mentions: Map<String, Int> = emptyMap(),
    val usage: Usage = Usage(),
) {
    val tokensIn: Long get() = usage.totalIn()
    val tokensOut: Long get() = usage.totalOut()
}

/**
 * Token counts as each agent records them. Claude repeats one API response's usage on every line
 * of that response, so the last response is held apart until the next one starts. Codex writes
 * running totals that restart after a resume, and newer versions also write one record per
 * response, which is preferred when present.
 */
@Serializable
internal data class Usage(
    val summedIn: Long = 0,
    val summedOut: Long = 0,
    val lastResponseId: String? = null,
    val lastResponseIn: Long = 0,
    val lastResponseOut: Long = 0,
    val runningIn: Long = 0,
    val runningOut: Long = 0,
    val earlierRunsIn: Long = 0,
    val earlierRunsOut: Long = 0,
    val recordsIn: Long = 0,
    val recordsOut: Long = 0,
) {
    fun totalIn(): Long = when {
        recordsIn + recordsOut > 0 -> recordsIn
        runningIn + earlierRunsIn > 0 -> earlierRunsIn + runningIn
        else -> summedIn + lastResponseIn
    }

    fun totalOut(): Long = when {
        recordsIn + recordsOut > 0 -> recordsOut
        runningIn + earlierRunsIn > 0 -> earlierRunsOut + runningOut
        else -> summedOut + lastResponseOut
    }
}

/** The running facts of one file while its new lines are read. */
internal class Tally(start: FileFacts) {
    var newestAt: Long? = start.newestAt
    var firstUserText: String? = start.firstUserText
    var ref: String? = start.ref
    var cwd: String? = start.cwd
    var side: Boolean = start.side
    private val mentions = HashMap(start.mentions)
    private var usage = start.usage

    fun seen(at: Long?) {
        if (at != null && (newestAt ?: Long.MIN_VALUE) < at) newestAt = at
    }

    fun firstUser(text: String?) {
        if (firstUserText == null && !text.isNullOrBlank()) firstUserText = oneLine(text, FIRST_TEXT_CHARS)
    }

    /** One line of a Claude response; lines with the same [responseId] repeat the same usage. */
    fun responseUsage(responseId: String?, tokensIn: Long, tokensOut: Long) {
        usage = if (responseId != null && responseId == usage.lastResponseId) {
            usage.copy(lastResponseIn = tokensIn, lastResponseOut = tokensOut)
        } else {
            usage.copy(
                summedIn = usage.summedIn + usage.lastResponseIn,
                summedOut = usage.summedOut + usage.lastResponseOut,
                lastResponseId = responseId,
                lastResponseIn = tokensIn,
                lastResponseOut = tokensOut,
            )
        }
    }

    /** Codex's running totals; a smaller total means the count restarted. */
    fun runningTotals(tokensIn: Long, tokensOut: Long) {
        val restarted = tokensIn < usage.runningIn || tokensOut < usage.runningOut
        usage = usage.copy(
            earlierRunsIn = usage.earlierRunsIn + if (restarted) usage.runningIn else 0,
            earlierRunsOut = usage.earlierRunsOut + if (restarted) usage.runningOut else 0,
            runningIn = tokensIn,
            runningOut = tokensOut,
        )
    }

    /** Codex's usage of one response. */
    fun responseRecord(tokensIn: Long, tokensOut: Long) {
        usage = usage.copy(recordsIn = usage.recordsIn + tokensIn, recordsOut = usage.recordsOut + tokensOut)
    }

    fun mention(worktree: String) {
        val count = mentions[worktree]
        if (count != null) mentions[worktree] = count + 1 else if (mentions.size < MAX_MENTIONED) mentions[worktree] = 1
    }

    fun facts(base: FileFacts, offset: Long, head: String) = base.copy(
        offset = offset,
        head = head,
        newestAt = newestAt,
        firstUserText = firstUserText,
        ref = ref,
        cwd = cwd,
        side = side,
        mentions = HashMap(mentions),
        usage = usage,
    )

    private companion object {
        const val FIRST_TEXT_CHARS = 200
        const val MAX_MENTIONED = 64
    }
}
