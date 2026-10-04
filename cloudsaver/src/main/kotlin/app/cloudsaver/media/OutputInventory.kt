package app.cloudsaver.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.cloudsaver.core.logic.OutputRoots
import app.cloudsaver.core.logic.ScanSources

/**
 * What is actually inside the output folders right now (MediaStore view):
 * the current ones and every folder a copy still waits in. Used for
 * gone-detection (CONFIRMED / USER_DELETED), the anchor rule and old-install
 * cleanup.
 */
class OutputInventory(private val context: Context) {

    data class Entry(
        val mediaStoreId: Long,
        val uri: Uri,
        val name: String,
        val relPath: String,
        val bytes: Long,
        val isVideo: Boolean,
        val dateTaken: Long,
        val ownedByUs: Boolean
    )

    /**
     * How many photos and videos of the person's own [path] holds - files
     * this app did not make. Null when the gallery could not be read, which
     * no caller may take for "none".
     */
    fun othersIn(path: String): Int? =
        query(listOf(path))?.count { !it.ownedByUs && !ScanSources.isPipelineName(it.name) }

    /**
     * The contents of [roots] and every folder inside them, or null if any
     * part could not be read. The default is every folder the app owns as of
     * the last options read; maintenance passes add the folders rows say
     * their copies were released into.
     *
     * Absence from this list is evidence: callers read it as "the cloud app
     * removed the copy after uploading it". A failed query would produce an
     * empty list, which is indistinguishable from every copy having been
     * uploaded - so a partial answer must be reported as no answer.
     */
    fun query(roots: Collection<String> = OutputRoots.owned): List<Entry>? {
        if (roots.isEmpty()) return null
        val out = mutableListOf<Entry>()
        val volumes = try {
            MediaStore.getExternalVolumeNames(context)
        } catch (e: Exception) {
            return null
        }
        if (volumes.isEmpty()) return null
        for (volume in volumes) {
            val ok =
                queryCollection(MediaStore.Images.Media.getContentUri(volume), false, roots, out) &&
                    queryCollection(MediaStore.Video.Media.getContentUri(volume), true, roots, out)
            if (!ok) return null
        }
        return out
    }

    /** Returns false if the collection could not be read. */
    private fun queryCollection(
        collection: Uri,
        isVideo: Boolean,
        roots: Collection<String>,
        out: MutableList<Entry>
    ): Boolean {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME
        )
        val (selection, args) = OutputRoots.likeClause(MediaStore.MediaColumns.RELATIVE_PATH, roots)
        return try {
            val cursor = context.contentResolver
                .query(collection, projection, selection, args, null)
                ?: return false
            cursor.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val iOwner = c.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    out += Entry(
                        mediaStoreId = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(iName) ?: continue,
                        relPath = c.getString(iRel) ?: "",
                        bytes = c.getLong(iSize),
                        isVideo = isVideo,
                        dateTaken = c.getLong(iTaken),
                        ownedByUs = c.getString(iOwner) == context.packageName
                    )
                }
            }
            true
        } catch (e: Exception) {
            // A hiccup is not proof of anything; maintain retries next pass.
            false
        }
    }
}
