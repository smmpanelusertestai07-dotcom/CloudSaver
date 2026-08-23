package app.cloudsaver.core.logic

/**
 * The folder paths, written out.
 *
 * Every screen that matters - the cloud step in setup, the layout setting,
 * Storage, the FAQ - has to print the same path, because the user has to type
 * or pick that exact string in a different app. Guessing it from a screenshot
 * is how people end up backing up their whole gallery by mistake.
 */
object OutputPaths {

    /** The folders in use for a layout, in the order they should be listed. */
    fun forMode(mode: OutputMode): List<String> = when (mode) {
        OutputMode.SINGLE -> listOf(Defaults.OUTPUT_DIR)
        OutputMode.SEPARATE -> listOf(Defaults.OUTPUT_DIR_PHOTOS, Defaults.OUTPUT_DIR_VIDEOS)
    }

    /** One line, for places with room for a sentence rather than a list. */
    fun joined(mode: OutputMode): String = forMode(mode).joinToString(" and ")

    /** Folders the other layout uses - still watched until they run empty. */
    fun otherModeFolders(mode: OutputMode): List<String> =
        forMode(if (mode == OutputMode.SINGLE) OutputMode.SEPARATE else OutputMode.SINGLE)
}
