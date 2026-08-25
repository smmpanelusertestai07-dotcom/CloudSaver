package app.cloudsaver.util

import android.content.Context
import android.os.Build
import app.cloudsaver.BuildConfig

/**
 * A crash must leave a trace the user can find (BB3).
 *
 * The app has no internet permission, so there is no crash reporter and never
 * will be: a crash that writes nothing simply never happened as far as anyone
 * can tell. The handler appends the full story to the existing app log, sets
 * a flag the next launch turns into one honest card, and then hands the crash
 * to the previous handler so the system still shows its own dialog and books
 * its own record. Nothing is ever sent anywhere; sharing the log is a button
 * the user presses.
 */
object CrashLog {

    private const val FLAG_FILE = "crash_pending"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // A crash inside the crash handler must not eat the original:
            // whatever happens here, the previous handler still runs.
            try {
                record(app, thread, throwable)
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun record(context: Context, thread: Thread, throwable: Throwable) {
        val stack = throwable.stackTraceToString()
        AppLog.log(
            context, "crash",
            "app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "on Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}, thread ${thread.name}\n$stack"
        )
        // A plain file flag rather than prefs: DataStore cannot be written
        // synchronously, and the process is about to die.
        context.getFileStreamPath(FLAG_FILE).createNewFile()
    }

    /** True once, on the launch after a crash. */
    fun crashPending(context: Context): Boolean =
        context.getFileStreamPath(FLAG_FILE).exists()

    fun clearPending(context: Context) {
        context.getFileStreamPath(FLAG_FILE).delete()
    }

    /**
     * Test seam: writes the same record and flag a real crash would, without
     * killing the process.
     */
    fun simulateForTest(context: Context, throwable: Throwable) {
        record(context, Thread.currentThread(), throwable)
    }
}
