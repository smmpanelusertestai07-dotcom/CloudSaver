package app.cloudsaver.util

import kotlinx.coroutines.sync.Mutex

/**
 * One writer at a time, per domain (AA3.3).
 *
 * WorkManager's unique names stop two workers racing, but the UI can start a
 * trial run, an Optimise now, or a removal while a scheduled run is mid-way -
 * and two paths writing the same rows is how a batch gets counted twice or a
 * ledger entry lands with the wrong evidence. Three domains, three mutexes,
 * held at the entry points rather than sprinkled through the internals.
 */
object Locks {

    /** Releasing staged copies into the upload folder, and batch accounting. */
    val release = Mutex()

    /** Removing originals or light copies - every deletion path. */
    val reclaim = Mutex()

    /** Ledger writes: recording deliveries and merging snapshots. */
    val ledger = Mutex()
}
