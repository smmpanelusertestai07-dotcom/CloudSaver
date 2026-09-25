package com.pocketide.sync

import com.pocketide.core.AgentFiles
import com.pocketide.core.AppDirs
import com.pocketide.core.FileClass
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
 * What is synced from each room. Whether a file may leave the phone at all is decided by
 * [AgentFiles], the one classification shared with the check-post and the Your data screen:
 * only SYNC files are uploaded (allow-list first), and a credential is SECRET wherever it sits
 * (deny-list second). Logins, app-generated config (hooks, MCP entries, permission rules) and
 * caches never go. This object only says what kind of object a SYNC file becomes.
 */
internal object TrackRules {
    /** Folders and files walked in every room's home; [AgentFiles] decides file by file. */
    val roots = listOf(
        ".claude/CLAUDE.md", ".claude/rules", ".claude/projects", ".claude/history.jsonl", ".claude/plans",
        ".codex/AGENTS.md", ".codex/AGENTS.override.md", ".codex/sessions", ".codex/archived_sessions",
        ".codex/history.jsonl", ".codex/rules", ".codex/skills",
        ".gemini/GEMINI.md", ".gemini/AGENTS.md", ".gemini/config", ".gemini/antigravity", ".gemini/antigravity-cli",
    )

    private val memoryNames = setOf("CLAUDE.md", "AGENTS.md", "AGENTS.override.md", "GEMINI.md", "memory.txtpb")

    /** The kind of a file under a room's home, or null when it must not leave the phone. */
    fun homeKind(path: String): ObjectKind? {
        if (AgentFiles.classify(path) != FileClass.SYNC || transient(path.substringAfterLast('/'))) return null
        val name = path.substringAfterLast('/')
        return when {
            name in memoryNames || "/memory/" in path || "/rules/" in path || path.startsWith(".codex/skills/") -> ObjectKind.MEMORY
            // Antigravity's conversations are opaque files: when one is rewritten instead of
            // appended to, its changed prefix makes the whole file a new base piece.
            path.endsWith(".jsonl") || "/conversations/" in path -> ObjectKind.CHAT_PIECE
            else -> ObjectKind.AGENT_STATE
        }
    }

    /** A folder the walk never enters: a link, or a place where logins live. */
    fun skipFolder(relativePath: String): Boolean = AgentFiles.isSecret("$relativePath/")

    /** SQLite files are copied only while their agent's room is stopped and they have been still for a minute. */
    fun isDatabase(path: String): Boolean = path.endsWith(".db") || path.endsWith(".db-wal")

    /** History files are scanned for pasted secrets, which are masked before they leave the phone. */
    fun needsSecretScan(path: String): Boolean = AgentFiles.needsSecretScan(path)

    /** Media: images always; videos wait for Wi-Fi; never APKs, build outputs, archives or key files. */
    fun mediaSyncable(name: String): Boolean =
        !transient(name) && !AgentFiles.isSecret(name) && name.substringAfterLast('.', "").lowercase() !in NEVER_MEDIA

    fun isVideo(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in VIDEO

    /** Files still being written, or SQLite's shared-memory index, which is rebuilt on open. */
    private fun transient(name: String): Boolean {
        val n = name.lowercase()
        return TRANSIENT_SUFFIXES.any { n.endsWith(it) }
    }

    private val TRANSIENT_SUFFIXES = listOf(".tmp", ".part", ".lock", ".swp", "~", "-shm", "-journal", ".pocketide-part")
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

    /**
     * Every syncable file of every room. [roomRunning] says whether an agent's room is running:
     * its databases are skipped then, because a copy taken mid-write may not open (R8).
     */
    fun scan(matcher: SessionMatcher, tracks: Map<String, FileTrack>, roomRunning: (String) -> Boolean): List<Candidate> =
        agents().flatMap { agent -> homeFiles(agent, matcher, tracks, roomRunning(agent)) + mediaFiles(agent) }

    private fun homeFiles(agentId: String, matcher: SessionMatcher, tracks: Map<String, FileTrack>, running: Boolean): List<Candidate> {
        val home = dirs.roomHome(agentId)
        if (!isRealDirectory(home)) return emptyList()
        val out = ArrayList<Candidate>()
        for (root in TrackRules.roots) {
            walk(home, root) { path, file, facts ->
                val kind = TrackRules.homeKind(path) ?: return@walk
                if (running && TrackRules.isDatabase(path)) return@walk
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
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val rel = basePath.relativize(dir).toString().replace(File.separatorChar, '/')
                    return if (attrs.isSymbolicLink || TrackRules.skipFolder(rel)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

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
    fun locate(kind: ObjectKind, agentId: String?, path: String): File? = roomFile(kind, agentId, path)?.file

    /** [locate], with the room folder the file sits in. */
    fun roomFile(kind: ObjectKind, agentId: String?, path: String): RoomFile? {
        if (kind == ObjectKind.SECRETS || agentId == null || !isSafeName(agentId)) return null
        val base = if (kind.root == Root.WORK) dirs.roomWork(agentId) else dirs.roomHome(agentId)
        return safeChild(base, path)?.let { RoomFile(base, path, it) }
    }

    companion object {
        const val MEDIA = ".media"
        private const val MAX_DEPTH = 12
        private const val HEAD_BYTES = 64 * 1024
        private val CWD = Regex("\"cwd\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
}
