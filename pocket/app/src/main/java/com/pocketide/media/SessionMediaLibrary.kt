package com.pocketide.media

import android.net.Uri
import com.pocketide.core.AppDirs
import com.pocketide.core.AppJson
import com.pocketide.core.Clock
import com.pocketide.model.SessionRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Something the owner can act on, in one plain sentence. */
class MediaException(message: String) : Exception(message)

/** What the sync engine knows, for the "backed up" mark (best effort). */
data class BackupFacts(
    /** Sessions with bytes not yet confirmed by Drive. */
    val waitingSessions: Set<String>,
    /** When Drive last confirmed everything (UTC ms), if known. */
    val upToDateAt: Long?,
)

/**
 * Each session's media folder, [AppDirs.sessionMedia]: outside the git worktree, synced with the
 * session. Files are named "<first 8 hex of SHA-256>-<safe name>" and stored once per content.
 * Where each file came from is kept in the app's private storage, not in the synced folder.
 */
internal class SessionMediaLibrary(
    private val dirs: AppDirs,
    private val sessions: () -> List<SessionRecord>,
    private val backup: () -> BackupFacts,
    private val shrinker: ImageShrinker,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    /** Private folder for "where it came from" records, one file per session. */
    private val metaDir: File,
    /** Private scratch folder (not visible inside Linux) where files are checked before they are stored. */
    private val stagingDir: File,
    /** Hands a file in the share folder to other apps (a FileProvider URI on the phone). */
    private val uriFor: (File) -> Uri,
    private val pollMs: Long = POLL_MS,
) : MediaLibrary {

    @Serializable
    data class Meta(val source: String, val createdAt: Long, val sha256: String)

    private val lock = Mutex()
    private val kinds = ConcurrentHashMap<String, Pair<Long, MediaKind>>()
    @Volatile private var lastUpToDate: Long? = null

    override fun forSession(sessionId: String): Flow<List<MediaItem>> = flow {
        var last: List<MediaItem>? = null
        while (true) {
            val now = scan(sessionId)
            if (now != last) {
                emit(now)
                last = now
            }
            delay(pollMs)
        }
    }.flowOn(io)

    override suspend fun add(sessionId: String, source: File, name: String, from: String): MediaItem {
        require(from in SOURCES) { "Unknown media source." }
        val session = session(sessionId) ?: throw MediaException("This session is not on this phone.")
        return withContext(io) {
            lock.withLock { store(session, source, name, from) }
        }
    }

    override fun kindOf(name: String, head: ByteArray): MediaKind = MediaSniffer.kindOf(name, head)

    override suspend fun delete(item: MediaItem) = withContext(io) {
        lock.withLock {
            val file = item.file
            if (!insideWork(file) || Files.isSymbolicLink(file.toPath())) throw MediaException("This file is not in a session's media.")
            if (file.exists() && !file.delete()) throw MediaException("Could not delete ${item.name}.")
            kinds.remove(file.path)
            val metas = readMeta(item.sessionId)
            if (file.name in metas) writeMeta(item.sessionId, metas - file.name)
        }
    }

    override fun shareUri(item: MediaItem): Uri {
        if (!insideWork(item.file) || !item.file.isFile) throw MediaException("This file is no longer on the phone.")
        cleanShareFolder()
        val folder = File(dirs.share, UUID.randomUUID().toString())
        if (!folder.mkdirs()) throw MediaException("Could not prepare the file for sharing.")
        val copy = File(folder, item.name)
        item.file.copyTo(copy)
        return uriFor(copy)
    }

    /** Lists a session's folder; hidden, partial and linked files are not media. */
    private fun scan(sessionId: String): List<MediaItem> {
        val session = session(sessionId) ?: return emptyList()
        val folder = folderOf(session) ?: return emptyList()
        val files = folder.listFiles()?.filter { isMediaFile(it) }.orEmpty()
        if (files.isEmpty()) return emptyList()
        val metas = readMeta(sessionId)
        val facts = backupFacts()
        return files.map { file ->
            val meta = metas[file.name]
            val kind = kindOfFile(file)
            val createdAt = meta?.createdAt ?: file.lastModified()
            MediaItem(
                sessionId = sessionId,
                file = file,
                name = file.name,
                kind = kind,
                bytes = file.length(),
                createdAt = createdAt,
                onPhone = true,
                backedUp = backedUp(session, kind, file, facts),
                source = meta?.source ?: SOURCE_AGENT,
            )
        }.sortedByDescending { it.createdAt }
    }

    private fun store(session: SessionRecord, source: File, name: String, from: String): MediaItem {
        val folder = folderOf(session) ?: throw MediaException("The session's media folder was replaced by a link. Remove the link and try again.")
        if (!folder.isDirectory && !folder.mkdirs()) throw MediaException("Could not create the session's media folder.")
        // Everything below works on a private copy: the source and the media folder are writable
        // from inside Linux, so a file could be swapped for a link half-way.
        val stage = File(stagingDir, UUID.randomUUID().toString())
        try {
            stagingDir.mkdirs()
            copyNoFollow(source, stage, name)
            val head = MediaSniffer.head(stage)
            val kind = MediaSniffer.kindOf(name, head)
            val limit = MediaSniffer.limitFor(kind)
            if (stage.length() > limit) throw MediaException("${safeName(name)} is larger than ${limit / MB} MB, the most Media keeps for this kind of file.")
            val sha = sha256(stage)
            val metas = readMeta(session.id)
            // An agent may have written the file straight into the media folder: it is renamed, not copied.
            val inPlace = source.parentFile?.canonicalFile == folder.canonicalFile
            existing(folder, sha, metas)?.let { stored ->
                if (inPlace && stored.canonicalFile != source.canonicalFile) source.delete()
                return itemOf(session, stored, kindOfFile(stored), metas[stored.name])
            }
            val stem = "${sha.take(HASH_PREFIX)}-${safeStem(name)}"
            val (content, extension) = shrunk(stage, from, kind, head) ?: (stage to MediaSniffer.extensionFor(kind, head, name))
            val target = freeName(folder, stem, extension)
            moveInto(content, target)
            val meta = Meta(from, clock.now(), sha)
            writeMeta(session.id, metas + (target.name to meta))
            if (inPlace) source.delete()
            if (kind == MediaKind.APK) keepLastApks(session.projectId)
            return itemOf(session, target, kindOfFile(target), meta)
        } catch (e: IOException) {
            throw MediaException("Could not save ${safeName(name)}: the phone may be full.")
        } finally {
            stage.delete()
            File(stage.path + WEBP_SUFFIX).delete()
        }
    }

    /** Copies [source] without following a link, and stops at the largest size Media keeps. */
    private fun copyNoFollow(source: File, target: File, name: String) {
        val path = source.toPath()
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            throw MediaException("${safeName(name)} is not on the phone any more.")
        }
        if (!attributes.isRegularFile) throw MediaException("Only plain files can be added to Media.")
        if (attributes.size() > MediaSniffer.VIDEO_LIMIT) throw MediaException("${safeName(name)} is larger than any file Media keeps.")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            target.outputStream().use { out ->
                val buffer = ByteArray(COPY_BUFFER)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MediaSniffer.VIDEO_LIMIT) throw MediaException("${safeName(name)} is larger than any file Media keeps.")
                    out.write(buffer, 0, n)
                }
            }
        }
    }

    /** Moves a staged file into the media folder, in one step when the file system allows it. */
    private fun moveInto(content: File, target: File) {
        try {
            Files.move(content.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            val part = File(target.parentFile, ".${target.name}.part")
            try {
                Files.copy(content.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS)
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                part.delete()
            }
        }
    }

    /** Screenshots from PocketIDE's own tools become WebP, when that is smaller. */
    private fun shrunk(stage: File, from: String, kind: MediaKind, head: ByteArray): Pair<File, String>? {
        if (from != SOURCE_AGENT || kind != MediaKind.IMAGE || !MediaSniffer.isPngOrJpeg(head)) return null
        val webp = File(stage.path + WEBP_SUFFIX)
        val smaller = try {
            shrinker.toWebp(stage, webp)
        } catch (e: OutOfMemoryError) {
            false
        }
        if (smaller && MediaSniffer.kindOf(webp.name, MediaSniffer.head(webp)) == MediaKind.IMAGE) return webp to "webp"
        webp.delete()
        return null
    }

    /** The same content already stored under its hash prefix. */
    private fun existing(folder: File, sha: String, metas: Map<String, Meta>): File? {
        val prefix = sha.take(HASH_PREFIX) + "-"
        return folder.listFiles()?.filter { it.name.startsWith(prefix) && isMediaFile(it) }?.firstOrNull { file ->
            val meta = metas[file.name]
            if (meta != null) meta.sha256 == sha else sha256(file) == sha
        }
    }

    /** The build outputs kept on the phone: the newest few APKs of a project, across its sessions. */
    private fun keepLastApks(projectId: String) {
        val apks = sessions().filter { it.projectId == projectId }.flatMap { s ->
            folderOf(s)?.listFiles()?.filter { isMediaFile(it) && kindOfFile(it) == MediaKind.APK }.orEmpty().map { s.id to it }
        }
        val byAge = apks.sortedByDescending { (sessionId, file) -> readMeta(sessionId)[file.name]?.createdAt ?: file.lastModified() }
        for ((sessionId, file) in byAge.drop(KEEP_APKS)) {
            if (file.delete()) {
                kinds.remove(file.path)
                writeMeta(sessionId, readMeta(sessionId) - file.name)
            }
        }
    }

    private fun itemOf(session: SessionRecord, file: File, kind: MediaKind, meta: Meta?) = MediaItem(
        sessionId = session.id,
        file = file,
        name = file.name,
        kind = kind,
        bytes = file.length(),
        createdAt = meta?.createdAt ?: file.lastModified(),
        onPhone = true,
        backedUp = backedUp(session, kind, file, backupFacts()),
        source = meta?.source ?: SOURCE_AGENT,
    )

    private fun backedUp(session: SessionRecord, kind: MediaKind, file: File, facts: BackupFacts): Boolean {
        // APKs never go to Drive: the newest few stay on the phone, the rest are on GitHub.
        if (!session.backUp || kind == MediaKind.APK || session.id in facts.waitingSessions) return false
        val confirmed = facts.upToDateAt ?: return false
        return file.lastModified() <= confirmed
    }

    private fun backupFacts(): BackupFacts {
        val facts = runCatching(backup).getOrElse { BackupFacts(emptySet(), null) }
        val latest = listOfNotNull(lastUpToDate, facts.upToDateAt).maxOrNull()
        lastUpToDate = latest
        return facts.copy(upToDateAt = latest)
    }

    private fun kindOfFile(file: File): MediaKind {
        val stamp = file.length() xor file.lastModified()
        kinds[file.path]?.let { (seen, kind) -> if (seen == stamp) return kind }
        val kind = MediaSniffer.kindOf(file.name, MediaSniffer.head(file))
        kinds[file.path] = stamp to kind
        return kind
    }

    private fun session(sessionId: String) = sessions().firstOrNull { it.id == sessionId }

    /**
     * The session's media folder, or null when a link inside the room's work folder points it
     * somewhere else (an agent can write there, and must not steer the app into other files).
     */
    private fun folderOf(session: SessionRecord): File? {
        val folder = dirs.sessionMedia(session.agentId, session.projectId, session.id)
        val expected = File(dirs.work.canonicalFile, folder.relativeTo(dirs.work).path)
        return folder.takeIf { it.canonicalPath == expected.path }
    }

    private fun isMediaFile(file: File): Boolean {
        val name = file.name
        if (name.startsWith(".") || name.endsWith(".part")) return false
        return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    private fun insideWork(file: File): Boolean = file.canonicalFile.toPath().startsWith(dirs.work.canonicalFile.toPath())

    private fun freeName(folder: File, stem: String, extension: String): File {
        var candidate = File(folder, "$stem.$extension")
        var n = 2
        while (Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) candidate = File(folder, "$stem-${n++}.$extension")
        return candidate
    }

    private fun cleanShareFolder() {
        val cutoff = clock.now() - SHARE_KEEP_MS
        dirs.share.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    private fun metaFile(sessionId: String): File? =
        if (SAFE_ID.matches(sessionId)) File(metaDir, "$sessionId.json") else null

    private fun readMeta(sessionId: String): Map<String, Meta> {
        val file = metaFile(sessionId)?.takeIf { it.isFile } ?: return emptyMap()
        return try {
            AppJson.decodeFromString(META_MAP, file.readText())
        } catch (e: IllegalArgumentException) {
            emptyMap()
        } catch (e: IOException) {
            emptyMap()
        }
    }

    private fun writeMeta(sessionId: String, metas: Map<String, Meta>) {
        val file = metaFile(sessionId) ?: return
        if (metas.isEmpty()) {
            file.delete()
            return
        }
        metaDir.mkdirs()
        val temp = File(metaDir, "${file.name}.tmp")
        temp.writeText(AppJson.encodeToString(META_MAP, metas))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val SOURCE_AGENT = "agent"
        const val SOURCE_ACTIONS = "actions"
        const val SOURCE_YOU = "you"
        private val SOURCES = setOf(SOURCE_AGENT, SOURCE_ACTIONS, SOURCE_YOU)

        private const val POLL_MS = 2_000L
        private const val HASH_PREFIX = 8
        private const val KEEP_APKS = 3
        private const val MB = 1024 * 1024
        private const val MAX_STEM = 80
        private const val COPY_BUFFER = 64 * 1024
        private const val WEBP_SUFFIX = ".webp"
        private const val SHARE_KEEP_MS = 24 * 60 * 60 * 1000L
        private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,128}")
        private val UNSAFE = Regex("[^A-Za-z0-9._-]+")
        private val META_MAP = MapSerializer(String.serializer(), Meta.serializer())

        /** A name without folders, odd characters or a leading dot. */
        fun safeName(name: String): String {
            val base = name.replace('\\', '/').substringAfterLast('/')
            val clean = UNSAFE.replace(base, "_").trim('.', '_', '-')
            return clean.ifEmpty { "file" }
        }

        /** [safeName] without its extension, short enough for any file system. */
        fun safeStem(name: String): String {
            val safe = safeName(name)
            val stem = if (safe.contains('.')) safe.substringBeforeLast('.') else safe
            return stem.trim('.', '_', '-').take(MAX_STEM).ifEmpty { "file" }
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
