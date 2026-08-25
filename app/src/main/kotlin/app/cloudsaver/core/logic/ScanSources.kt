package app.cloudsaver.core.logic

/**
 * Decides which gallery folders the scanner and the album picker may touch.
 *
 * Compressed copies are still images and videos, so MediaStore hands them
 * back like any other photo. Left alone the app would re-compress its own
 * output - and any other tool's - shrinking copies of copies forever. It also
 * picked up files such as `_ente_keep.jpg`, which a cloud app keeps to stop a
 * folder disappearing.
 *
 * A folder is refused when it is ours, hidden, named like a known pipeline's
 * output, or when its contents simply look like pipeline output.
 */
object ScanSources {

    /** Folder names earlier versions of this app, and Ente, write copies to. */
    val LEGACY_OUTPUT_NAMES: Set<String> = setOf(
        "EnteUpload", "GlassSaver", "LiteSaver", "CloudShrink", "CloudSaver"
    )

    /**
     * Compressed copies are named `<stem>__<16 hex>.<ext>`, optionally with
     * MediaStore's " (1)" de-duplication suffix.
     */
    private val PIPELINE_NAME = Regex(""".*__[0-9a-f]{16}(\s\(\d+\))?\.[A-Za-z0-9]+$""")

    /** Share of pipeline-looking names above which a folder is treated as output. */
    const val HEURISTIC_THRESHOLD = 0.8

    /** Smallest sample worth judging; two files prove nothing. */
    const val HEURISTIC_MIN_FILES = 4

    fun isPipelineName(name: String): Boolean = PIPELINE_NAME.matches(name)

    /** True when a folder's contents look like another pipeline's output. */
    fun looksLikePipelineOutput(fileNames: List<String>): Boolean {
        if (fileNames.size < HEURISTIC_MIN_FILES) return false
        val matches = fileNames.count { isPipelineName(it) }
        return matches.toDouble() / fileNames.size >= HEURISTIC_THRESHOLD
    }

    /** True for a path Android hides from the gallery. */
    fun isHiddenPath(relativePath: String?): Boolean =
        !relativePath.isNullOrEmpty() &&
            relativePath.split('/').any { it.isNotEmpty() && it.startsWith(".") }

    /** True for a folder named after a known pipeline's output. */
    fun isLegacyOutputName(bucketName: String?): Boolean =
        bucketName != null && LEGACY_OUTPUT_NAMES.any { it.equals(bucketName, ignoreCase = true) }

    /**
     * The reason a folder is off limits, or null when it may be scanned.
     * Everything the scanner and the picker refuse goes through here, so the
     * two can never disagree about what is eligible.
     */
    fun exclusionReason(
        relativePath: String?,
        bucketName: String?,
        looksLikeOutput: Boolean = false
    ): Reason? = when {
        Defaults.isAppOwnedPath(relativePath) -> Reason.OUR_OUTPUT
        isHiddenPath(relativePath) -> Reason.HIDDEN
        isLegacyOutputName(bucketName) -> Reason.LEGACY_OUTPUT
        looksLikeOutput -> Reason.LOOKS_LIKE_OUTPUT
        else -> null
    }

    enum class Reason { OUR_OUTPUT, HIDDEN, LEGACY_OUTPUT, LOOKS_LIKE_OUTPUT, CLOUD_LOCAL }

    /**
     * True for a path inside a known cloud app's own media directory (Z4.2).
     *
     * Cloud apps keep downloaded and cached copies under Android/media/<their
     * package>, and MediaStore indexes those like any photo. Scanning them
     * would optimise the cloud's own downloads - copies of copies. Folders
     * holding a .nomedia file never reach MediaStore's media collections at
     * all, so that half of the rule is enforced by the platform itself.
     */
    fun isCloudLocalPath(relativePath: String?, cloudPackages: Collection<String>): Boolean {
        if (relativePath.isNullOrEmpty()) return false
        val lower = relativePath.lowercase()
        return cloudPackages.any { pkg ->
            lower.contains("android/media/${pkg.lowercase()}")
        }
    }

    /**
     * The 16-hex identifier inside an output-pattern name, or null (Z4.1).
     *
     * `IMG_0001__a1b2c3d4e5f60718.jpg` carries the original's fingerprint in
     * its own name, which is what lets a copy that came back from the cloud -
     * into Download, into another album, anywhere - be recognised with no
     * stored state at all. Recognised means never optimised again: shrinking
     * a copy of a copy is the loop this whole object exists to stop.
     */
    private val PIPELINE_ID = Regex("""__([0-9a-f]{16})(?:\s\(\d+\))?\.[A-Za-z0-9]+$""")

    fun pipelineIdOf(name: String): String? =
        PIPELINE_ID.find(name)?.groupValues?.get(1)
}
