package app.cloudsaver.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
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

    // ---- deaths the handler above cannot see --------------------------------

    /** Marks how far through the exit history the last look got. */
    private const val SEEN_FILE = "exit_seen_at"

    /**
     * Asks Android how the process died last time, for the deaths a
     * `Thread.UncaughtExceptionHandler` can never witness.
     *
     * That handler sees JVM exceptions on a living process and nothing else.
     * This app spends its time in `MediaCodec`, so the plausible deaths are
     * precisely the ones it is blind to: a transcode dying in native code, an
     * ANR while a long video pass holds the foreground service, or the
     * low-memory killer taking the process while a 4K clip is in flight. Every
     * one of those wrote nothing at all, while the card on Home promised
     * "CloudSaver stopped unexpectedly last time" as the app's only crash
     * channel - so the app's one honest answer to "did it break?" was silent
     * about the ways it actually breaks.
     *
     * `getHistoricalProcessExitReasons` is the platform's own answer: on
     * device, no internet, no analytics. API 30+, so on 29 this is a no-op and
     * the JVM handler remains the whole story, which the log line says.
     *
     * Only records exits newer than the last one seen, so re-reading the same
     * history on every launch cannot fill the log with one death repeated.
     */
    fun recordPreviousExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val seenAt = context.getFileStreamPath(SEEN_FILE)
                .takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            var newest = seenAt
            // Oldest first, so the log reads in the order things happened.
            for (info in exits.sortedBy { it.timestamp }) {
                if (info.timestamp <= seenAt) continue
                newest = maxOf(newest, info.timestamp)
                if (!isFault(info.reason)) continue
                AppLog.log(
                    context, "crash",
                    "the previous run of app ${BuildConfig.VERSION_NAME} ended: " +
                        "${reasonName(info.reason)} (${info.description ?: "no detail"})"
                )
                // The same card the JVM handler raises: one honest sentence on
                // the next launch, whichever way the process died.
                context.getFileStreamPath(FLAG_FILE).createNewFile()
            }
            if (newest > seenAt) {
                context.getFileStreamPath(SEEN_FILE).writeText(newest.toString())
            }
        }
    }

    /**
     * Deaths worth telling the user about.
     *
     * A normal exit, the user swiping the app away, or the system trimming a
     * cached process are all ordinary Android and would turn the crash card
     * into noise nobody reads. These are the ones where the app stopped doing
     * what it was asked to do.
     */
    private fun isFault(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> true
        else -> false
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "an unhandled error"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "an error inside the video or photo encoder"
        ApplicationExitInfo.REASON_ANR -> "Android decided it had stopped responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "the phone ran out of memory"
        ApplicationExitInfo.REASON_SIGNALED -> "the system stopped the process"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "it was using too much of the phone"
        else -> "an unknown reason"
    }
}
