package app.cloudsaver.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny rotating file logger. Viewable and shareable from Help > Logs. */
object AppLog {

    private const val MAX_BYTES = 256 * 1024L
    private val lock = Any()

    fun file(context: Context): File {
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "cloudsaver.log")
    }

    fun log(context: Context, tag: String, msg: String) {
        synchronized(lock) {
            try {
                val f = file(context)
                if (f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, "cloudsaver.log.1")
                    old.delete()
                    f.renameTo(old)
                }
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file(context).appendText("$ts [$tag] ${redact(msg)}\n")
            } catch (e: Exception) {
                // Logging must never break the pipeline.
            }
        }
    }

    /** Logs never contain absolute file paths - only the last path segment. */
    private val PATH = Regex("""(?:/[\w.\-]+){2,}""")

    fun redact(msg: String): String =
        msg.replace(PATH) { m -> ".../" + m.value.substringAfterLast('/') }

    fun readTail(context: Context, maxChars: Int = 40_000): String {
        return try {
            val text = file(context).takeIf { it.exists() }?.readText() ?: ""
            if (text.length <= maxChars) text else text.substring(text.length - maxChars)
        } catch (e: Exception) {
            ""
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            try {
                file(context).delete()
                File(file(context).parentFile, "cloudsaver.log.1").delete()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
