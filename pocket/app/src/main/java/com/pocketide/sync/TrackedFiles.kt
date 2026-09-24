package com.pocketide.sync

import com.pocketide.core.AppDirs
import com.pocketide.model.ObjectKind
import com.pocketide.model.SessionRecord
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

/** A file on the phone that belongs in Drive, with what it is and whose it is. */
internal data class Candidate(
    val kind: ObjectKind,
    val agentId: String,
    val path: String,
    val file: File,
    val facts: FileFacts,
    val sessionId: String?,
    val video: Boolean,
    val headCwd: String? = null,
) {
    val key: String get() = fileKey(kind, agentId, path)
}

/**
 * What is synced from each room (researched from each agent's own documentation and files):
 * transcripts, memory and instructions, the agent state needed to resume a session, and the
 * session media folders. Never logins or tokens, never build outputs, never app-managed config.
 */
internal object TrackRules {
    private val claudeMemory = listOf(".claude/CLAUDE.md", ".claude/rules", ".claude/agents", ".claude/skills", ".claude/commands", ".claude/output-styles", ".claude/agent-memory", ".claude/workflows")
    private val codexMemory = listOf(".codex/AGENTS.md", ".codex/AGENTS.override.md", ".codex/prompts", ".codex/skills", ".codex/rules")
    private val agyMemory = listOf(
        ".gemini/GEMINI.md", ".gemini/AGENTS.md", ".gemini/config/GEMINI.md", ".gemini/config/AGENTS.md", ".gemini/config/rules",
        ".gemini/antigravity/knowledge", ".gemini/antigravity/global_workflows",
    )
    private const val AGY_SUMMARIES = ".gemini/antigravity/conversation_summaries.db"

    /** Folders and files walked in each room's home, by agent. Other agents sync their media only. */
    fun roots(agentId: String): List<String> = when (agentId) {
        "claude" -> listOf(".claude/projects", ".claude/history.jsonl", ".claude/tasks", ".claude/plans") + claudeMemory
        "codex" -> listOf(".codex/sessions", ".codex/archived_sessions", ".codex/history.jsonl") + codexMemory
        "antigravity" -> listOf(".gemini/antigravity/conversations", ".gemini/antigravity/brain", AGY_SUMMARIES, "$AGY_SUMMARIES-wal") + agyMemory
        else -> emptyList()
    }

    /** A database file is copied only once it has been still for a minute, so the copy is whole. */
    fun needsQuiet(path: String): Boolean = path.endsWith(".db") || path.endsWith(".db-wal")

    /** The kind of a file under a room's home, or null when it must never leave the phone. */
    fun homeKind(agentId: String, path: String): ObjectKind? {
        if (denied(path)) return null
        return when (agentId) {
            "claude" -> claudeKind(path)
            "codex" -> codexKind(path)
            "antigravity" -> agyKind(path)
            else -> null
        }
    }

    private fun claudeKind(path: String): ObjectKind? = when {
        path == ".claude/history.jsonl" -> ObjectKind.CHAT_PIECE
        path.startsWith(".claude/projects/") -> {
            val parts = path.split('/')
            when {
                parts.size > 4 && parts[3] == "memory" -> ObjectKind.MEMORY
                path.endsWith(".jsonl") -> ObjectKind.CHAT_PIECE
                else -> ObjectKind.AGENT_STATE
            }
        }
        path.startsWith(".claude/tasks/") || path.startsWith(".claude/plans/") -> ObjectKind.AGENT_STATE
        claudeMemory.any { path == it || path.startsWith("$it/") } -> ObjectKind.MEMORY
        else -> null
    }

    private fun codexKind(path: String): ObjectKind? = when {
        path == ".codex/history.jsonl" -> ObjectKind.CHAT_PIECE
        path.startsWith(".codex/sessions/") || path.startsWith(".codex/archived_sessions/") ->
            if (path.endsWith(".jsonl")) ObjectKind.CHAT_PIECE else ObjectKind.AGENT_STATE
        codexMemory.any { path == it || path.startsWith("$it/") } -> ObjectKind.MEMORY
        else -> null
    }

    private fun agyKind(path: String): ObjectKind? = when {
        path.startsWith(".gemini/antigravity/conversations/") -> ObjectKind.CHAT_PIECE
        path.startsWith(".gemini/antigravity/brain/") -> if (path.endsWith(".jsonl")) ObjectKind.CHAT_PIECE else ObjectKind.AGENT_STATE
        path == AGY_SUMMARIES || path == "$AGY_SUMMARIES-wal" -> ObjectKind.AGENT_STATE
        agyMemory.any { path == it || path.startsWith("$it/") } -> ObjectKind.MEMORY
        else -> null
    }

    /** Media: images always; videos wait for Wi-Fi; never APKs, build outputs or archives. */
    fun mediaSyncable(name: String): Boolean = !denied(name) && name.substringAfterLast('.', "").lowercase() !in NEVER_MEDIA

    fun isVideo(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in VIDEO

    /**
     * Logins, tokens and keys, wherever an agent keeps them (auth.json, .credentials.json, OAuth
     * token files, keyrings), app-managed config (which can hold MCP secrets), auto-installed
     * folders, and files still being written.
     */
    fun denied(path: String): Boolean = path.split('/').any { segment ->
        val s = segment.lowercase()
        s in DENIED_NAMES || s in DENIED_DIRS || "oauth" in s || "credential" in s ||
            DENIED_SUFFIXES.any { s.endsWith(it) } || DENIED_PREFIXES.any { s.startsWith(it) }
    }

    private val DENIED_NAMES = setOf(
        "auth.json", "google_accounts.json", "cookies", "cookies.json", "cookies.sqlite", ".netrc",
        ".claude.json", "mcp_config.json", "settings.json", "settings.local.json", "config.toml", "installation_id",
    )
    private val DENIED_DIRS = setOf("keyring", "keyrings", ".keyring", "backups", ".trash", ".system")
    private val DENIED_SUFFIXES = listOf(
        "token", "tokens.json", "token.json", ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore",
        ".tmp", ".part", ".lock", ".swp", "~", "-shm", "-journal",
    )
    private val DENIED_PREFIXES = listOf("id_rsa", "id_ed25519", "id_ecdsa", "id_dsa")
    private val VIDEO = setOf("mp4", "webm")
    private val NEVER_MEDIA = setOf(
        "apk", "aab", "apks", "xapk", "ipa", "app", "exe", "msi", "dmg", "deb", "rpm", "jar", "aar", "war",
        "so", "o", "a", "dylib", "dll", "zip", "tar", "gz", "tgz", "xz", "bz2", "7z", "rar", "zst",
    )
}

/** Finds which PocketIDE session a file belongs to. */
internal class SessionMatcher(sessions: Collection<SessionRecord>) {
    private val byWorktree = HashMap<String, String>()
    private val byClaudeDir = HashMap<String, String>()
    private val refs = ArrayList<Pair<String, String>>()

    init {
        for (s in sessions) {
            val guest = AppDirs.guestWorktree(s.projectId, s.id)
            byWorktree[guest] = s.id
            byClaudeDir[claudeDir(guest)] = s.id
            s.agentSessionRef?.trim()?.takeIf { it.length >= MIN_REF && !it.contains('/') }?.let { refs += it to s.id }
        }
    }

    /**
     * Claude keeps a folder per working directory, and each session has its own worktree; Codex
     * writes the working directory in a rollout's first line; otherwise the agent's own session id
     * ([SessionRecord.agentSessionRef]) in the file's path.
     */
    fun homeSession(path: String, cwd: String?): String? {
        if (path.startsWith(".claude/projects/")) {
            path.split('/').getOrNull(2)?.let { dir -> byClaudeDir[dir]?.let { return it } }
        }
        if (cwd != null) {
            byWorktree[cwd]?.let { return it }
            byWorktree.entries.firstOrNull { cwd.startsWith(it.key + "/") }?.let { return it.value }
        }
        val segments = path.split('/')
        return refs.firstOrNull { (ref, _) -> segments.any { it.contains(ref) } }?.second
    }

    companion object {
        private const val MIN_REF = 8

        /** Claude Code names a project folder after its working directory, every other character a dash. */
        fun claudeDir(path: String): String = path.replace(Regex("[^A-Za-z0-9]"), "-")
    }
}

/** Walks the rooms and the session media folders. Never follows a symbolic link. */
internal class Scanner(private val dirs: AppDirs) {

    fun agents(): List<String> {
        val fromRooms = dirs.rooms.listFiles()?.filter { isRealDirectory(it) }?.map { it.name }.orEmpty()
        val fromWork = dirs.work.listFiles()?.filter { isRealDirectory(it) }?.map { it.name }.orEmpty()
        return (fromRooms + fromWork).filter(::isSafeName).distinct().sorted()
    }

    fun scan(matcher: SessionMatcher, tracks: Map<String, FileTrack>): List<Candidate> =
        agents().flatMap { agent -> homeFiles(agent, matcher, tracks) + mediaFiles(agent) }

    private fun homeFiles(agentId: String, matcher: SessionMatcher, tracks: Map<String, FileTrack>): List<Candidate> {
        val home = dirs.roomHome(agentId)
        if (!isRealDirectory(home)) return emptyList()
        val out = ArrayList<Candidate>()
        for (root in TrackRules.roots(agentId)) {
            walk(home, root) { path, file, facts ->
                val kind = TrackRules.homeKind(agentId, path) ?: return@walk
                val track = tracks[fileKey(kind, agentId, path)]
                val rollout = kind == ObjectKind.CHAT_PIECE && (path.startsWith(".codex/sessions/") || path.startsWith(".codex/archived_sessions/"))
                val cwd = track?.headCwd ?: if (rollout && track?.sessionId == null) firstLineCwd(file) else null
                val session = track?.sessionId ?: matcher.homeSession(path, cwd)
                out += Candidate(kind, agentId, path, file, facts, session, video = false, headCwd = cwd)
            }
        }
        return out
    }

    /** `work/<agent>/<project>/.media/<session>/…`: the session is the folder name. */
    private fun mediaFiles(agentId: String): List<Candidate> {
        val work = dirs.roomWork(agentId)
        val projects = work.listFiles()?.filter { isRealDirectory(it) && isSafeName(it.name) }.orEmpty()
        val out = ArrayList<Candidate>()
        for (project in projects) {
            walk(work, "${project.name}/$MEDIA") { path, file, facts ->
                val parts = path.split('/')
                val session = parts.getOrNull(2)?.takeIf { parts.size > 3 && isSafeName(it) } ?: return@walk
                if (!TrackRules.mediaSyncable(file.name)) return@walk
                out += Candidate(ObjectKind.MEDIA, agentId, path, file, facts, session, TrackRules.isVideo(file.name))
            }
        }
        return out
    }

    private fun walk(base: File, relative: String, visit: (String, File, FileFacts) -> Unit) {
        val start = safeChild(base, relative) ?: return
        val startPath = start.toPath()
        if (!Files.exists(startPath)) return
        val basePath = base.canonicalFile.toPath()
        try {
            Files.walkFileTree(startPath, EnumSet.noneOf(java.nio.file.FileVisitOption::class.java), MAX_DEPTH, object : FileVisitor<Path> {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (attrs.isSymbolicLink || TrackRules.denied(dir.fileName.toString())) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) {
                        val rel = basePath.relativize(file).toString().replace(File.separatorChar, '/')
                        if (!rel.startsWith("..")) visit(rel, file.toFile(), FileFacts(attrs.size(), attrs.lastModifiedTime().toMillis()))
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (_: IOException) {
            // A folder vanished while it was walked; the next run sees the new state.
        }
    }

    /** The "cwd" a Codex rollout records in its first line (the session's worktree). */
    private fun firstLineCwd(file: File): String? = try {
        FileInputStream(file).use { input ->
            val head = ByteArray(HEAD_BYTES)
            val n = input.read(head)
            if (n <= 0) return null
            val line = String(head, 0, n, Charsets.UTF_8).substringBefore('\n')
            CWD.find(line)?.groupValues?.get(1)?.replace("\\/", "/")
        }
    } catch (_: IOException) {
        null
    }

    /** The file on this phone for a logical file, or null when the path is unsafe or not a file path. */
    fun locate(kind: ObjectKind, agentId: String?, path: String): File? {
        if (kind == ObjectKind.SECRETS || agentId == null || !isSafeName(agentId)) return null
        val base = if (kind.root == Root.WORK) dirs.roomWork(agentId) else dirs.roomHome(agentId)
        return safeChild(base, path)
    }

    companion object {
        const val MEDIA = ".media"
        private const val MAX_DEPTH = 12
        private const val HEAD_BYTES = 64 * 1024
        private val CWD = Regex("\"cwd\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
}
