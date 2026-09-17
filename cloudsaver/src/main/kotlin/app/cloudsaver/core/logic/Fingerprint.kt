package app.cloudsaver.core.logic

import java.io.InputStream
import java.security.MessageDigest

/**
 * Path-independent identity of an original media item:
 * fp16 = first 16 hex chars of SHA-1("displayName|size|dateModified").
 * Moving or renaming a folder keeps displayName/size/dateModified stable,
 * so the same file is never processed twice.
 */
object Fingerprint {

    fun fp16(displayName: String, sizeBytes: Long, dateModified: Long): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest("$displayName|$sizeBytes|$dateModified".toByteArray(Charsets.UTF_8))
        return toHex(digest).substring(0, 16)
    }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return toHex(md.digest())
    }

    /** Output naming: `<originalStem>__<fp16>.<ext>` */
    fun outputName(displayName: String, fp16: String, ext: String): String {
        val stem = displayName.substringBeforeLast('.', displayName)
        return "${stem}__${fp16}.$ext"
    }

    /** Recovers the fp16 from an output file name, or null if the name is not ours. */
    fun fpFromOutputName(name: String): String? {
        val stem = stripDedupSuffix(name).substringBeforeLast('.', name)
        val idx = stem.lastIndexOf("__")
        if (idx < 0) return null
        val fp = stem.substring(idx + 2)
        return if (fp.length == 16 && fp.all { it in "0123456789abcdef" }) fp else null
    }

    /** "stem__fp16 (1).jpg" -> "stem__fp16.jpg" (MediaStore de-dup suffix). */
    fun stripDedupSuffix(name: String): String =
        name.replace(Regex("""\s\(\d+\)(\.[A-Za-z0-9]+)$"""), "$1")

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}
