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
 */
object KeptCopies {

    fun belongsTo(fileName: String, displayName: String, fingerprint: String): Boolean {
        val own = stemOf(displayName)
        if (stemOf(fileName).equals(own, ignoreCase = true)) return true
        if (stemOf(Fingerprint.stripDedupSuffix(fileName)).equals(own, ignoreCase = true)) return true
        val fp = Fingerprint.fpFromOutputName(fileName) ?: return false
        return fp.equals(fingerprint, ignoreCase = true)
    }

    private fun stemOf(name: String): String = name.substringBeforeLast('.', name)
}
