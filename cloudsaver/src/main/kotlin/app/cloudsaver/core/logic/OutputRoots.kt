package app.cloudsaver.core.logic

/**
 * Where light copies go, as the person set it: one folder or one per type,
 * and for each either the default or a name of their own.
 *
 * Paths are MediaStore relative paths without the trailing slash
 * ("Pictures/EnteSaver/Photos"). An empty choice means "the default", so a
 * person who never touched the setting follows the default if it ever moves,
 * and one who picked a name keeps it.
 */
data class OutputLayout(
    val mode: OutputMode = OutputMode.SINGLE,
    val single: String = "",
    val photos: String = "",
    val videos: String = ""
) {
    /** The folder a copy of this kind is released into right now. */
    fun path(folder: OutFolder): String = when (folder) {
        OutFolder.SINGLE -> single.ifEmpty { Defaults.OUTPUT_DIR }
        OutFolder.PHOTOS -> photos.ifEmpty { Defaults.OUTPUT_DIR_PHOTOS }
        OutFolder.VIDEOS -> videos.ifEmpty { Defaults.OUTPUT_DIR_VIDEOS }
    }

    /** The folders in use for the current layout, in the order they are listed. */
    val current: List<String>
        get() = folders(mode).map { path(it) }

    /** The other layout's folders: still watched until they run empty. */
    val otherMode: List<String>
        get() = folders(if (mode == OutputMode.SINGLE) OutputMode.SEPARATE else OutputMode.SINGLE)
            .map { path(it) }

    /** True when the folder is a name the person chose rather than the default. */
    fun isCustom(folder: OutFolder): Boolean = when (folder) {
        OutFolder.SINGLE -> single.isNotEmpty()
        OutFolder.PHOTOS -> photos.isNotEmpty()
        OutFolder.VIDEOS -> videos.isNotEmpty()
    }

    /** Which of this layout's folders a relative path is, or null. */
    fun folderFor(relativePath: String): OutFolder? {
        val cleaned = OutputRoots.normalize(relativePath)
        // Photos and videos first: by default they sit inside the single folder.
        return listOf(OutFolder.PHOTOS, OutFolder.VIDEOS, OutFolder.SINGLE)
            .firstOrNull { OutputRoots.same(path(it), cleaned) }
    }

    companion object {
        fun folders(mode: OutputMode): List<OutFolder> = when (mode) {
            OutputMode.SINGLE -> listOf(OutFolder.SINGLE)
            OutputMode.SEPARATE -> listOf(OutFolder.PHOTOS, OutFolder.VIDEOS)
        }
    }
}

/**
 * Every folder light copies may be in, and the one rule that reads them.
 *
 * A copy that is no longer in its folder is read as "the cloud took it", and
 * that is what eventually offers an original for deletion. So the folders a
 * pass looks in must include every folder a copy is still waiting in - the
 * current ones, the ones the person moved away from, and the ones earlier
 * versions used - or a copy left behind in an old folder would vanish from
 * the listing and be counted as uploaded without ever having been sent.
 */
object OutputRoots {

    /** Folders this app has written copies to under any name it has had. */
    val LEGACY: List<String> = listOf(Defaults.LEGACY_OUTPUT_DIR)

    /**
     * The folders the scanner must leave alone, as of the last options read.
     *
     * Held here because the scanner, the album list and Free up space all ask
     * "is this ours?" from places that have no options at hand. It starts at
     * the defaults and the legacy folder, so a check made before the first
     * read is still right for everyone who kept the default; a folder of the
     * person's own is added the moment options are read. Copies are named so
     * the scanner recognises them anywhere, so this is the second guard, not
     * the only one.
     */
    @Volatile
    var owned: Set<String> = defaultsAndLegacy()
        private set

    fun remember(layout: OutputLayout, past: Collection<String>) {
        owned = watched(layout, past, emptyList())
    }

    /**
     * Every folder a maintenance pass has to list: the current layout, the
     * other layout, folders moved away from, folders rows say they released
     * into, and the defaults and legacy folders.
     */
    fun watched(
        layout: OutputLayout,
        past: Collection<String>,
        releasedInto: Collection<String>
    ): Set<String> {
        val all = LinkedHashSet<String>()
        for (p in layout.current + layout.otherMode + past + releasedInto + defaultsAndLegacy()) {
            val n = normalize(p)
            if (n.isNotEmpty() && all.none { same(it, n) }) all += n
        }
        return all
    }

    /**
     * Copies waiting in the old folder [root] or a folder inside it, given
     * [perFolder] (folder to waiting copies). Copies in a folder still in use
     * ([inUse]) are not the old folder's, even when it sits inside it -
     * Pictures/EnteSaver/Photos inside an old Pictures/EnteSaver - or the old
     * folder would never be let go.
     */
    fun waitingIn(root: String, perFolder: Map<String, Int>, inUse: Collection<String>): Int =
        perFolder.entries.sumOf { (path, n) ->
            if (isUnder(path, root) && inUse.none { isUnder(path, it) }) n else 0
        }

    /**
     * The fewest roots that still cover every folder: a folder inside another
     * one is listed by the outer folder's query already.
     */
    fun outermost(roots: Collection<String>): List<String> =
        roots.map { normalize(it) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
            .let { list -> list.filter { r -> list.none { o -> o != r && isUnder(r, o) } } }

    /** True for [path] itself or anything inside it. */
    fun isUnder(path: String?, root: String): Boolean {
        if (path.isNullOrEmpty()) return false
        val p = normalize(path).lowercase()
        val r = normalize(root).lowercase()
        return r.isNotEmpty() && (p == r || p.startsWith("$r/"))
    }

    fun isOwned(relativePath: String?): Boolean = owned.any { isUnder(relativePath, it) }

    /**
     * SQL for "inside any of these folders", with LIKE's own wildcards
     * escaped: a folder called "my_photos" must not also match "my-photos".
     * The trailing slash keeps Pictures/EnteSaverOld out of Pictures/EnteSaver.
     */
    fun likeClause(column: String, roots: Collection<String>): Pair<String, Array<String>> {
        val list = outermost(roots)
        val clause = list.joinToString(" OR ", "(", ")") { "$column LIKE ? ESCAPE '\\'" }
        return clause to list.map { escapeLike(it) + "/%" }.toTypedArray()
    }

    fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** MediaStore's relative paths end in a slash; ours do not. */
    fun normalize(path: String): String = path.trim().trim('/')

    fun same(a: String, b: String): Boolean = normalize(a).equals(normalize(b), ignoreCase = true)

    private fun defaultsAndLegacy(): Set<String> = linkedSetOf(
        Defaults.OUTPUT_DIR, Defaults.OUTPUT_DIR_PHOTOS, Defaults.OUTPUT_DIR_VIDEOS
    ) + LEGACY
}
