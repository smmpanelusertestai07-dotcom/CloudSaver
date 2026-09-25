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

/**
 * Antigravity keeps each conversation in `~/.gemini/<product>/brain/<conversation-id>/`, with its
 * log at `.system_generated/logs/transcript.jsonl` (the product folder is `antigravity`,
 * `antigravity-cli` or `antigravity-ide`). As its own documentation describes the file, each line
 * is one step: `type` (USER_INPUT, PLANNER_RESPONSE, tool steps), `source`, `created_at`,
 * `content`, `tool_calls` and `media` (files on disk, not bytes). A step records no working
 * directory, so a conversation belongs to the session whose worktree path its steps name most.
 */
internal object AntigravityFormat : TranscriptFormat {
    override val id = "antigravity"

    val PRODUCTS = listOf("antigravity", "antigravity-cli", "antigravity-ide")
    const val LOG = ".system_generated/logs/transcript.jsonl"
    private const val CONVERSATIONS = "conversations"

    /** A session worktree path as the rooms see it: /work/<owner>__<repo>/<session uuid>. */
    private val WORKTREE = Regex(
        "/work/[A-Za-z0-9._-]+/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
    )

    /**
     * The whole conversation folder belongs to the session (its artifacts and logs), and so does
     * the conversation's own file in `conversations/`, which Antigravity needs to reopen it.
     */
    override fun locate(home: File, sessions: List<SessionRecord>, source: FactsSource): Map<String, SessionTranscript> {
        val folders = PRODUCTS.flatMap { SafeFiles.children(File(home, ".gemini/$it/brain")) }.filter(SafeFiles::isDirectory)
        if (folders.isEmpty()) return emptyMap()
        val byWorktree = sessions.associateBy { AppDirs.guestWorktree(it.projectId, it.id) }
        val grouped = HashMap<String, MutableList<Pair<Found, File>>>()
        for (folder in folders) {
            val log = File(folder, LOG)
            if (!SafeFiles.isFile(log)) continue
            val facts = source.read(log)
            val owner = facts.mentions.entries
                .filter { it.key in byWorktree }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .firstOrNull()?.let { byWorktree.getValue(it.key) } ?: continue
            grouped.getOrPut(owner.id) { ArrayList() } += Found(log, facts.copy(ref = folder.name)) to folder
        }
        return grouped.mapValues { (_, found) ->
            SessionTranscript(found.map { it.first }, found.flatMap { (_, folder) -> listOf(folder) + conversationFiles(folder) })
        }
    }

    /** `<product>/conversations/<id>.pb` (and any sibling named after the id) of `<product>/brain/<id>`. */
    private fun conversationFiles(brainFolder: File): List<File> {
        val product = brainFolder.parentFile?.parentFile ?: return emptyList()
        return SafeFiles.children(File(product, CONVERSATIONS))
            .filter { SafeFiles.isFile(it) && it.name.substringBefore('.') == brainFolder.name }
    }

    override fun entries(line: JsonObject): List<TranscriptEntry> {
        val at = isoMillis(line.text("created_at") ?: line.text("createdAt"))
        return when (stepType(line)) {
            "USER_INPUT" -> {
                val text = line.text("content").orEmpty()
                val images = images(line)
                if (text.isBlank() && images == 0) emptyList() else listOf(TranscriptEntry(USER, clip(text), at, images))
            }
            "PLANNER_RESPONSE" -> buildList {
                val text = line.text("content").orEmpty()
                val images = images(line)
                if (text.isNotBlank() || images > 0) add(TranscriptEntry(ASSISTANT, clip(text), at, images))
                (line.array("tool_calls") ?: line.array("toolCalls")).orEmpty()
                    .mapNotNull { it.asObject() }
                    .forEach { add(TranscriptEntry(TOOL, toolCall(it), at)) }
            }
            else -> emptyList()
        }
    }

    override fun count(raw: String, line: JsonObject?, tally: Tally) {
        WORKTREE.findAll(raw).forEach { tally.mention(it.value) }
        if (line == null) return
        tally.seen(isoMillis(line.text("created_at") ?: line.text("createdAt")))
        if (tally.firstUserText == null && stepType(line) == "USER_INPUT") tally.firstUser(line.text("content"))
    }

    private fun stepType(line: JsonObject) = line.text("type")?.removePrefix("CORTEX_STEP_TYPE_")

    private fun images(line: JsonObject) = line.array("media").orEmpty().count { item ->
        val media = item.asObject()
        (media?.text("mime_type") ?: media?.text("mimeType"))?.startsWith("image/") == true
    }

    private fun toolCall(call: JsonObject): String {
        val name = call.text("name") ?: call.text("tool_name") ?: call.text("toolName")
        val args = call.obj("args") ?: call.obj("arguments") ?: call.obj("input")
            ?: (call.text("args") ?: call.text("arguments"))?.let(::parseObject)
        return ToolSummary.of(name, args)
    }
}
