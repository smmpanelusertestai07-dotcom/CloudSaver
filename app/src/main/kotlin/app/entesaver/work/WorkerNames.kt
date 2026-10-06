package app.entesaver.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/**
 * Makes the app's workers by name, including the names they had before 12.0.
 *
 * WorkManager stores each piece of scheduled work with its worker's full
 * class name and keeps it for as long as the work exists - for the periodic
 * maintenance pass, which is enqueued with KEEP, that is forever. Until 12.0
 * the code lived under the app's permanent id, so a phone updated from an
 * earlier version still holds work under the names below. Without this, WorkManager
 * could not find the class, and maintenance - the pass that notices Ente's
 * uploads - would never run again.
 */
object WorkerNames : WorkerFactory() {

    /** The pre-12.0 class name of each worker, and how to make it today. */
    private val PREVIOUS: Map<String, (Context, WorkerParameters) -> ListenableWorker> = mapOf(
        "app.cloudsaver.work.CompressWorker" to ::CompressWorker,
        "app.cloudsaver.work.MaintainWorker" to ::MaintainWorker
    )

    /** The names this factory answers for (tested against the worker classes). */
    val previousNames: Set<String> get() = PREVIOUS.keys

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? =
        // Null hands every current name to WorkManager's own factory.
        PREVIOUS[workerClassName]?.invoke(appContext, workerParameters)
}
