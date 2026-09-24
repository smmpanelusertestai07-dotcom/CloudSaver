package com.pocketide.sessions.transcripts

import com.pocketide.core.AppDirs
import com.pocketide.model.SessionRecord
import com.pocketide.projects.SafeFiles
import com.pocketide.sessions.TranscriptEntry
import com.pocketide.sessions.transcripts.TranscriptFormat.Companion.ASSISTANT
import com.pocketide.sessions.transcripts.TranscriptFormat.Companion.TOOL
import com.pocketide.sessions.transcripts.TranscriptFormat.Companion.USER
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.math.abs

/**
 * Claude Code keeps each conversation in `~/.claude/projects/<project>/<session-id>.jsonl`, where
 * `<project>` is the working directory with every character other than an ASCII letter or digit
 * replaced by "-" (names over 200 characters are cut and end with a hash of the path). Lines are
 * API messages: text, tool_use and image blocks, with the response's usage on assistant lines.
 */
internal object ClaudeFormat : TranscriptFormat {
    override val id = "claude"

    private const val MAX_KEY = 200

    /** The folder name Claude Code uses for [cwd] (the same algorithm as its own code). */
    fun projectKey(cwd: String): String {
        val key = buildString(cwd.length) {
            for (c in cwd) append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') c else '-')
        }
        if (key.length <= MAX_KEY) return key
        // Its hash is Java's String.hashCode over UTF-16 units; the absolute value is taken as a
        // double in JavaScript, hence Long here.
        return key.take(MAX_KEY) + "-" + abs(cwd.hashCode().toLong()).toString(36)
    }

    /**
     * A session's folders are the one named after its worktree and any named after a folder inside
     * it. The conversation files are the `.jsonl` files there; what Claude Code keeps beside them
     * (subagents' threads, large tool results, set-aside copies) belongs to the session too, except
     * the `memory` folder, which is agent memory and synced as such.
     */
    override fun locate(home: File, sessions: List<SessionRecord>, source: FactsSource): Map<String, SessionTranscript> {
        val folders = SafeFiles.children(File(home, PROJECTS)).filter(SafeFiles::isDirectory)
        if (folders.isEmpty()) return emptyMap()
        val found = HashMap<String, SessionTranscript>()
        for (session in sessions) {
            val key = projectKey(AppDirs.guestWorktree(session.projectId, session.id))
            val entries = folders.filter { it.name == key || it.name.startsWith("$key-") }.flatMap(SafeFiles::children)
            if (entries.isEmpty()) continue
            val conversations = entries
                .filter { SafeFiles.isFile(it) && it.name.endsWith(".jsonl") && SET_ASIDE !in it.name }
                .map { file ->
                    val facts = source.read(file)
                    Found(file, if (facts.ref == null) facts.copy(ref = file.name.removeSuffix(".jsonl")) else facts)
                }
            found[session.id] = SessionTranscript(conversations, entries.filter { it.name != MEMORY })
        }
        return found
    }

    override fun entries(line: JsonObject): List<TranscriptEntry> {
        if (line["isSidechain"].asFlag() || line["isMeta"].asFlag() || line["isCompactSummary"].asFlag()) return emptyList()
        val message = line.obj("message") ?: return emptyList()
        val at = isoMillis(line.text("timestamp"))
        return when (line.text("type")) {
            "user" -> listOfNotNull(userEntry(message, at))
            "assistant" -> assistantEntries(message, at)
            else -> emptyList()
        }
    }

    override fun count(raw: String, line: JsonObject?, tally: Tally) {
        if (line == null) return
        tally.seen(isoMillis(line.text("timestamp")))
        if (tally.ref == null) tally.ref = line.text("sessionId")
        val message = line.obj("message") ?: return
        when (line.text("type")) {
            "assistant" -> message.obj("usage")?.let { usage ->
                tally.responseUsage(
                    responseId = message.text("id"),
                    tokensIn = usage.number("input_tokens") + usage.number("cache_creation_input_tokens") +
                        usage.number("cache_read_input_tokens"),
                    tokensOut = usage.number("output_tokens"),
                )
            }
            "user" -> if (tally.firstUserText == null && !line["isSidechain"].asFlag() && !line["isMeta"].asFlag()) {
                tally.firstUser(userEntry(message, null)?.text)
            }
        }
    }

    private fun userEntry(message: JsonObject, at: Long?): TranscriptEntry? {
        val content = message["content"]
        content.asText()?.let { raw -> return typed(raw)?.let { TranscriptEntry(USER, clip(it), at) } }
        val texts = ArrayList<String>()
        var images = 0
        for (block in content.asArray().orEmpty()) {
            val part = block.asObject() ?: continue
            when (part.text("type")) {
                "text" -> part.text("text")?.let(::typed)?.let(texts::add)
                "image" -> images++
            }
        }
        if (texts.isEmpty() && images == 0) return null
        return TranscriptEntry(USER, clip(texts.joinToString("\n\n")), at, images)
    }

    private fun assistantEntries(message: JsonObject, at: Long?): List<TranscriptEntry> {
        val content = message["content"]
        content.asText()?.let { return if (it.isBlank()) emptyList() else listOf(TranscriptEntry(ASSISTANT, clip(it), at)) }
        val entries = ArrayList<TranscriptEntry>()
        val text = StringBuilder()
        var images = 0
        fun flush() {
            if (text.isNotBlank() || images > 0) entries += TranscriptEntry(ASSISTANT, clip(text.toString()), at, images)
            text.setLength(0)
            images = 0
        }
        for (block in content.asArray().orEmpty()) {
            val part = block.asObject() ?: continue
            when (part.text("type")) {
                "text" -> part.text("text")?.let {
                    if (text.isNotEmpty()) text.append("\n\n")
                    text.append(it)
                }
                "image" -> images++
                "tool_use", "server_tool_use" -> {
                    flush()
                    entries += TranscriptEntry(TOOL, ToolSummary.of(part.text("name"), part.obj("input")), at)
                }
            }
        }
        flush()
        return entries
    }

    /**
     * What the owner typed. Slash commands are stored wrapped in tags and read as "/name args";
     * command output, caveats and reminders the app added are not the owner's words.
     */
    fun typed(raw: String): String? {
        val text = SYSTEM_REMINDER.replace(raw, "").trim()
        if (text.isEmpty() || HIDDEN.any { text.startsWith(it) }) return null
        if (text.startsWith("<command-")) {
            val name = COMMAND_NAME.find(text)?.groupValues?.get(1)?.trim() ?: return null
            val args = COMMAND_ARGS.find(text)?.groupValues?.get(1)?.trim().orEmpty()
            return if (args.isEmpty()) name else "$name $args"
        }
        return text
    }

    private const val PROJECTS = ".claude/projects"
    private const val MEMORY = "memory"
    private const val SET_ASIDE = ".orphaned-"
    private val SYSTEM_REMINDER = Regex("<system-reminder>[\\s\\S]*?</system-reminder>")
    private val COMMAND_NAME = Regex("<command-name>([\\s\\S]*?)</command-name>")
    private val COMMAND_ARGS = Regex("<command-args>([\\s\\S]*?)</command-args>")
    private val HIDDEN = listOf("<local-command-stdout>", "<local-command-stderr>", "<local-command-caveat>")
}
