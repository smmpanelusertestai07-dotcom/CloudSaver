package app.entesaver.core.logic

/**
 * The folder paths, written out.
 *
 * Every screen that matters - the Ente step in setup, the folder setting,
 * Storage, the FAQ - has to print the same path, because the user has to pick
 * that exact folder in a different app. Guessing it from a screenshot is how
 * people end up backing up their whole gallery by mistake.
 */
object OutputPaths {

    /** The folders in use, in the order they should be listed. */
    fun current(layout: OutputLayout): List<String> = layout.current

    /** One line, for places with room for a sentence rather than a list. */
    fun joined(layout: OutputLayout): String = current(layout).joinToString(" and ")

    /**
     * Which of the layout's folders a MediaStore RELATIVE_PATH is, or null if
     * it is none of them. MediaStore hands back a trailing slash, and either
     * arrangement may be in use, so every folder of the layout is checked.
     */
    fun folderFor(relativePath: String, layout: OutputLayout): OutFolder? =
        layout.folderFor(relativePath)
}
