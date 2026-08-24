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

    enum class Reason { OUR_OUTPUT, HIDDEN, LEGACY_OUTPUT, LOOKS_LIKE_OUTPUT }
}
