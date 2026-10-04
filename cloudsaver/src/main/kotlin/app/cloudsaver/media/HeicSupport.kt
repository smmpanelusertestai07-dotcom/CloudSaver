package app.cloudsaver.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.core.content.edit
import androidx.heifwriter.HeifWriter
import app.cloudsaver.BuildConfig
import java.io.File

/**
 * Whether this phone can write HEIC photos well.
 *
 * Phones that list a HEIC or HEVC encoder do not all make good HEIC files:
 * some write ones that other apps cannot open, some take seconds a photo.
 * So the answer is never assumed from a list. The first run that wants HEIC
 * makes one small picture on the phone's own hardware, reads it back, and
 * times it; a pass is remembered until the phone's software changes, and a
 * fail sends every photo to JPEG. Three photos that then fail to encode undo
 * a pass. Nothing about this runs on a screen - only inside the background
 * work, where a second or two is nobody's wait.
 */
object HeicSupport {

    enum class State { UNKNOWN, OK, FAIL }

    private const val PREFS = "heic"
    private const val KEY_STAMP = "stamp"
    private const val KEY_STATE = "state"
    private const val KEY_FAILS = "fails"

    /** How long the test picture may take, start to finish, on a pass. */
    const val PROBE_LIMIT_MS = 5_000L

    /** Encode failures on real photos that undo a pass. */
    const val FAILS_TO_DEMOTE = 3

    private const val PROBE_W = 1024
    private const val PROBE_H = 768

    /** The phone's build and this app's: either changing means asking again. */
    private val stamp: String
        get() = "${Build.FINGERPRINT}#${BuildConfig.VERSION_CODE}"

    fun state(context: Context): State {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_STAMP, null) != stamp) return State.UNKNOWN
        return runCatching { State.valueOf(prefs.getString(KEY_STATE, "") ?: "") }
            .getOrDefault(State.UNKNOWN)
    }

    fun works(context: Context): Boolean = state(context) == State.OK

    /**
     * Answers the question once for this build of the phone, making the test
     * picture if it has not been made yet. Blocking; call from background work.
     */
    fun probeIfNeeded(context: Context, tempDir: File): State {
        val known = state(context)
        if (known != State.UNKNOWN) return known
        // A fail is written down before the test, and on disk before it
        // starts: a test that takes the whole app down with it throws
        // nothing, and without this the next run would test again on its
        // first photo - and go down again, every run, with nothing optimised.
        record(context, State.FAIL, fails = 0, now = true)
        val result = if (EncoderCaps.hasHardwareHeic() && testEncode(tempDir)) State.OK else State.FAIL
        record(context, result, fails = 0)
        return result
    }

    /** One real photo failed to encode as HEIC; enough of them undo a pass. */
    fun noteFailure(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fails = prefs.getInt(KEY_FAILS, 0) + 1
        record(context, if (fails >= FAILS_TO_DEMOTE) State.FAIL else state(context), fails)
    }

    private fun record(context: Context, state: State, fails: Int, now: Boolean = false) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = now) {
            putString(KEY_STAMP, stamp)
            putString(KEY_STATE, state.name)
            putInt(KEY_FAILS, fails)
        }
    }

    private fun testEncode(tempDir: File): Boolean {
        val file = File(tempDir, "heic_probe_${System.nanoTime()}.heic")
        val bitmap = Bitmap.createBitmap(PROBE_W, PROBE_H, Bitmap.Config.ARGB_8888)
        val started = System.currentTimeMillis()
        return try {
            // A gradient rather than a flat colour, so the encoder has real
            // work to do and a broken one cannot pass by writing nothing.
            Canvas(bitmap).drawPaint(
                Paint().apply {
                    shader = LinearGradient(
                        0f, 0f, PROBE_W.toFloat(), PROBE_H.toFloat(),
                        0xFF2A6CF0.toInt(), 0xFFF2A541.toInt(), Shader.TileMode.CLAMP
                    )
                }
            )
            write(bitmap, file, quality = 80, exif = null, timeoutMs = PROBE_LIMIT_MS)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val elapsed = System.currentTimeMillis() - started
            file.length() > 0 && bounds.outWidth == PROBE_W && bounds.outHeight == PROBE_H &&
                elapsed <= PROBE_LIMIT_MS
        } catch (e: Throwable) {
            false
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    /**
     * Writes [bitmap] to [file] as HEIC. Throws when the phone's encoder will
     * not do it, which callers answer with JPEG.
     */
    fun write(bitmap: Bitmap, file: File, quality: Int, exif: ByteArray?, timeoutMs: Long) {
        // Closed through AutoCloseable, as the library's own sample does.
        HeifWriter.Builder(
            file.absolutePath, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP
        ).setQuality(quality.coerceIn(0, 100)).setMaxImages(1).build().use { writer ->
            writer.start()
            writer.addBitmap(bitmap)
            if (exif != null) writer.addExifData(0, exif, 0, exif.size)
            writer.stop(timeoutMs)
        }
    }
}
