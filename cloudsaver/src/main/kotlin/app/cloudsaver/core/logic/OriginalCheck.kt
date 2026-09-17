package app.cloudsaver.core.logic

/**
 * Whether the file at an original's address is still the original.
 *
 * A row remembers its original by MediaStore id, and an id survives an edit:
 * "Save" in a gallery editor rewrites the bytes under the same number. The
 * scanner then records the edited photo as a new row, but the old row - the
 * one whose copy the cloud actually holds - still points at that number, and
 * Free up space offered it: "the cloud has this", when the cloud had the
 * photo as it was before the edit. Confirming would have removed the edited
 * version, which nothing had ever collected.
 *
 * So before an original is put in front of Android's dialog, the file is
 * read back and must still carry the row's name and exact size. Not the
 * modified date: MediaProvider rewrites that on its own when it re-stats a
 * file, and a benign rewrite must not lock a real backup out of Free up. A
 * gallery edit that lands on the same byte count is not a case worth a
 * rule; a rename is, and it fails the name test, which is the safe side.
 */
object OriginalCheck {

    fun unchanged(rowName: String, rowSize: Long, fileName: String, fileSize: Long): Boolean {
        if (rowSize <= 0 || fileSize != rowSize) return false
        return stemOf(fileName).equals(stemOf(rowName), ignoreCase = true)
    }

    private fun stemOf(name: String): String = name.substringBeforeLast('.', name)
}
