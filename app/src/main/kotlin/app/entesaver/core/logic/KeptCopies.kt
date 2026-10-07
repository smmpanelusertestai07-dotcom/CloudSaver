package app.entesaver.core.logic

/**
 * Whether a gallery file can be the light copy a row remembers.
 *
 * A row's keptUri is a MediaStore id, and an id means something only on the
 * phone that issued it. The snapshot that now carries keptUri across a
 * reinstall is a plain file under Download, and a phone-to-phone transfer
 * copies plain files - so on the new phone every restored id points at
 * whatever that phone happens to hold under the same number. Two things act
 * on a keptUri: the scanner leaves that file alone, and "Remove the light
 * copy" deletes it. Both ask this first, so a stale id costs nothing instead
 * of somebody's photo.
 *
 * A kept copy is either in place, under the original's own stem (the
 * extension may differ - a HEIC original's copy is a JPEG), or in the app's
 * own album under the pipeline name that carries the row's fingerprint. A
 * copy someone renamed by hand fails both tests and becomes an ordinary photo
 * again; there is no way to tell a renamed copy from a stranger's file that
 * happens to share its number, and the safe reading is the stranger's.
 *
 * The one rename that is not a person's is MediaProvider's own: an in-place
 * copy is written while the original still sits beside it under the same
 * name, so it lands as "IMG_1234 (1).jpg". That suffix is the provider's, not
 * a choice anyone made, and the copy still belongs.
 *
 * Under DCIM the provider renames the way a camera would. A name like
 * "IMG_20240101_123456" becomes "IMG_20240101_123456~2", which is still
 * plainly the original's and belongs. A name like "IMG_1234" takes the next
 * free number, "IMG_1235", which cannot be told from the camera's next real
 * photo - so an in-place copy never asks for such a name (inPlaceRequest).
 * 12.1 did ask for it, and only its history lookup reads such a copy back
 * (isLegacyCountUp).
 */
object KeptCopies {

    /** MediaProvider's camera-style name: "IMG_20240101_123456", optionally "~2". */
    private val DCF_RELAXED = Regex("""((?:IMG|MVIMG|VID)_[0-9]{8}_[0-9]{6})(?:~([0-9]+))?""")

    /** MediaProvider's numbered camera name, "IMG_1234": it renames by counting up. */
    private val DCF_STRICT = Regex("""([A-Z0-9_]{4})([0-9]{4})""")

    fun belongsTo(fileName: String, displayName: String, fingerprint: String): Boolean {
        val own = stemOf(displayName)
        if (stemOf(fileName).equals(own, ignoreCase = true)) return true
        if (stemOf(Fingerprint.stripDedupSuffix(fileName)).equals(own, ignoreCase = true)) return true
        if (isCameraRename(stemOf(fileName), own)) return true
        val fp = Fingerprint.fpFromOutputName(fileName) ?: return false
        return fp.equals(fingerprint, ignoreCase = true)
    }

    /**
     * The name to ask for when a copy goes in beside its original [name], in
     * the folder [relativePath].
     *
     * Usually the name itself, and the provider adds " (1)". In a DCIM folder
     * a numbered camera name would come back as the next number instead, which
     * belongsTo can never safely accept, so the provider's ordinary suffix is
     * asked for up front.
     */
    fun inPlaceRequest(relativePath: String, name: String): String {
        val dcim = relativePath.split('/').any { it == "DCIM" }
        val stem = stemOf(name)
        if (!dcim || !DCF_STRICT.matches(stem)) return name
        val ext = name.substringAfterLast('.', "")
        return if (ext.isEmpty()) "$stem (1)" else "$stem (1).$ext"
    }

    /**
     * Whether [copyName] is a 12.1 in-place copy of [originalName] that the
     * provider numbered under DCIM: "IMG_1234.jpg" landed as "IMG_1235.jpg",
     * or a later number if that was taken.
     *
     * 12.1 asked for the original's own name and kept what it got. The name
     * alone is also the camera's next real photo, so the copy must carry the
     * original's modified time too - 12.1 stamped it on every in-place copy.
     * Only the lookup for a 12.1 history entry may ask this; the scanner and
     * "Remove the light copy" never do (belongsTo).
     */
    fun isLegacyCountUp(
        copyName: String,
        originalName: String,
        copyModified: Long,
        originalModified: Long
    ): Boolean {
        if (copyModified <= 0L || copyModified != originalModified) return false
        val ext = copyName.substringAfterLast('.', "")
        if (!ext.equals(originalName.substringAfterLast('.', ""), ignoreCase = true)) return false
        val copy = DCF_STRICT.matchEntire(stemOf(copyName)) ?: return false
        val mine = DCF_STRICT.matchEntire(stemOf(originalName)) ?: return false
        return copy.groupValues[1] == mine.groupValues[1] &&
            copy.groupValues[2].toInt() > mine.groupValues[2].toInt()
    }

    /**
     * A row's state once its light copy is removed.
     *
     * Still reclaimed, just without the local copy now: FREED_KEPT becomes
     * FREED, and must not go back in the queue, because the cloud has it. A
     * row whose original came back from the trash is DONE and stays DONE -
     * its original is on the phone, and calling it freed would be wrong.
     */
    fun stateAfterRemoval(state: String): String =
        if (state == ItemState.FREED_KEPT.name) ItemState.FREED.name else state

    /**
     * "~N" added by the provider: the same camera name, with a number past
     * the original's own, because the provider counts up from there.
     */
    private fun isCameraRename(stem: String, own: String): Boolean {
        val file = DCF_RELAXED.matchEntire(stem) ?: return false
        val mine = DCF_RELAXED.matchEntire(own) ?: return false
        if (file.groupValues[1] != mine.groupValues[1]) return false
        val n = file.groupValues[2].toIntOrNull() ?: return false
        val m = mine.groupValues[2].toIntOrNull() ?: 1
        return n > m
    }

    private fun stemOf(name: String): String = name.substringBeforeLast('.', name)
}
