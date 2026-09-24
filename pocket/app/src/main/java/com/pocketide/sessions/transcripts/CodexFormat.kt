package com.pocketide.sessions.transcripts

import com.pocketide.core.AppDirs
import com.pocketide.model.SessionRecord
import com.pocketide.projects.SafeFiles
import com.pocketide.sessions.TranscriptEntry
import com.pocketide.sessions.transcripts.TranscriptFormat.Companion.ASSISTANT
import com.pocketide.sessions.transcripts.TranscriptFormat.Companion.TOOL
import com.pocketide.sessions.transcripts.TranscriptFormat.Companion.USER
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Codex keeps each thread in `~/.codex/sessions/YYYY/MM/DD/rollout-<time>-<id>.jsonl` (and
 * `archived_sessions/`). Every line is `{timestamp, type, payload}`; the first is `session_meta`
 * with the thread's `cwd`. Messages are `response_item`s in every Codex version, so the view is
 * built from them (the older `event_msg` copies of the same messages are not repeated). Codex may
 * compress threads idle for a week into `.jsonl.zst`, which the view cannot read.
 */
internal object CodexFormat : TranscriptFormat {
    override val id = "codex"

    const val COMPRESSED_SUFFIX = ".zst"

    /**
     * A thread belongs to the session it started in (its `cwd` is the worktree or a folder in it).
     * A compressed thread cannot be opened here: it keeps the facts read before it was compressed,
     * or is recognised by the session's saved reference to it.
     */
    override fun locate(home: File, sessions: List<SessionRecord>, source: FactsSource): Map<String, SessionTranscript> {
        val rollouts = FOLDERS.flatMap { SafeFiles.files(File(home, it), MAX_DEPTH) }
            .filter { it.name.startsWith("rollout-") && (it.name.endsWith(".jsonl") || it.name.endsWith(".jsonl$COMPRESSED_SUFFIX")) }
        if (rollouts.isEmpty()) return emptyMap()
        val byWorktree = sessions.associateBy { AppDirs.guestWorktree(it.projectId, it.id) }
        val grouped = HashMap<String, MutableList<Found>>()
        for (file in rollouts) {
            val compressed = file.name.endsWith(COMPRESSED_SUFFIX)
            val plain = if (compressed) File(file.path.removeSuffix(COMPRESSED_SUFFIX)) else file
            val plainRef = TranscriptFormat.guestPath(home, plain)
            val facts = if (compressed) source.carried(file, plain) else source.read(file)
            val owner = facts?.cwd?.let { cwd ->
                byWorktree.entries.firstOrNull { (worktree, _) -> cwd == worktree || cwd.startsWith("$worktree/") }?.value
            } ?: sessions.firstOrNull { it.agentSessionRef == plainRef } ?: continue
            val known = facts ?: FileFacts(file.absolutePath, id)
            grouped.getOrPut(owner.id) { ArrayList() } += Found(file, known.copy(ref = plainRef))
        }
        return grouped.mapValues { (_, found) -> SessionTranscript(found, found.map { it.file }) }
    }

    override fun entries(line: JsonObject): List<TranscriptEntry> {
        if (line.text("type") != "response_item") return emptyList()
        val item = line.obj("payload") ?: return emptyList()
        val at = isoMillis(line.text("timestamp"))
        return when (item.text("type")) {
            "message" -> listOfNotNull(message(item, at))
            "function_call" -> listOf(tool(ToolSummary.ofJsonArguments(item.text("name"), item.text("arguments")), at))
            "custom_tool_call" -> listOf(tool(ToolSummary.ofFreeform(item.text("name"), item.text("input")), at))
            "local_shell_call" -> listOf(tool(localShell(item), at))
            "web_search_call" -> listOf(tool(ToolSummary.of("web search", item.obj("action")), at))
            else -> emptyList()
        }
    }

    override fun count(raw: String, line: JsonObject?, tally: Tally) {
        if (line == null) return
        tally.seen(isoMillis(line.text("timestamp")))
        val payload = line.obj("payload") ?: return
        when (line.text("type")) {
            "session_meta" -> {
                if (tally.cwd == null) tally.cwd = payload.text("cwd")
                if (tally.ref == null) tally.ref = payload.text("id")
                if (payload.text("parent_thread_id") != null) tally.side = true
            }
            "response_item" -> if (tally.firstUserText == null && payload.text("type") == "message" && payload.text("role") == "user") {
                tally.firstUser(message(payload, null)?.text)
            }
            "event_msg" -> if (payload.text("type") == "token_count") {
                payload.obj("info")?.obj("total_token_usage")?.let {
                    tally.runningTotals(it.number("input_tokens"), it.number("output_tokens"))
                }
            }
            "token_usage_record" -> payload.obj("usage")?.let {
                tally.responseRecord(it.number("input_tokens"), it.number("output_tokens"))
            }
        }
    }

    private fun message(item: JsonObject, at: Long?): TranscriptEntry? {
        val parts = item.array("content") ?: return null
        return when (item.text("role")) {
            "user" -> userMessage(parts, at)
            "assistant" -> texts(parts, "output_text", "input_text").takeIf { it.isNotBlank() }
                ?.let { TranscriptEntry(ASSISTANT, clip(it), at) }
            else -> null
        }
    }

    /** What the owner typed; context Codex adds as user messages (instructions, environment) is not. */
    private fun userMessage(parts: JsonArray, at: Long?): TranscriptEntry? {
        val texts = parts.mapNotNull { it.asObject()?.takeIf { p -> p.text("type") == "input_text" }?.text("text") }
        if (texts.any(::isContext)) return null
        val shown = texts.filterNot { IMAGE_LABEL.matches(it.trim()) }.joinToString("\n\n").trim()
        val images = parts.count { it.asObject()?.text("type") == "input_image" }
        if (shown.isEmpty() && images == 0) return null
        return TranscriptEntry(USER, clip(shown), at, images)
    }

    /**
     * Codex wraps the context it adds in one tag per fragment (`<environment_context>…`) or in the
     * AGENTS.md instructions block; a fragment like that is not something the owner wrote.
     */
    fun isContext(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.startsWith(AGENTS_MD) && trimmed.endsWith(AGENTS_MD_END)) return true
        val tag = OPENING_TAG.find(trimmed) ?: return false
        return trimmed.endsWith("</${tag.groupValues[1]}>")
    }

    private fun texts(parts: JsonArray, vararg types: String) = parts
        .mapNotNull { it.asObject()?.takeIf { p -> p.text("type") in types }?.text("text") }
        .joinToString("\n\n")

    private fun localShell(item: JsonObject): String {
        val command = item.obj("action")?.array("command")?.let(ToolSummary::commandOf)
        return oneLine(if (command.isNullOrBlank()) "shell" else "shell: $command", SUMMARY_CHARS)
    }

    private fun tool(summary: String, at: Long?) = TranscriptEntry(TOOL, summary, at)

    private val FOLDERS = listOf(".codex/sessions", ".codex/archived_sessions")
    private const val MAX_DEPTH = 4
    private const val SUMMARY_CHARS = 160
    private const val AGENTS_MD = "# AGENTS.md instructions"
    private const val AGENTS_MD_END = "</INSTRUCTIONS>"
    private val OPENING_TAG = Regex("^<([A-Za-z_][A-Za-z0-9_-]*)(?:\\s[^>]*)?>")
    private val IMAGE_LABEL = Regex("</?(?:local_)?(?:image|audio)\\b[^>]*>")
}
