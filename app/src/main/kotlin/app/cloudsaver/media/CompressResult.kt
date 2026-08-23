package app.cloudsaver.media

import java.io.File

/** Outcome of compressing (or as-is copying) one media item into a temp file. */
data class CompressResult(
    val file: File,
    val bytes: Long,
    val asIs: Boolean,
    val reason: String,
    val ext: String
)
