package app.entesaver.util

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

    /**
     * The maintenance pass: evidence, self-heal, snapshots, clean-up.
     *
     * It had no lock of any kind, and two paths start it - the hourly
     * MaintainWorker, and CompressWorker at the end of every compression run.
     * Those are different unique work names, so WorkManager does not serialise
     * them, and the UI can ask for a confirm pass on top. Two passes over the
     * same rows is how self-heal reverts an item another pass has just
     * released: back to NEW, staged file forgotten, and sent to the cloud a
     * second time. This engine takes none of the three locks above, so holding
     * this one at its entry points cannot deadlock against them.
     */
    val maintain = Mutex()

    /**
     * Making one light copy: the scheduled run and the Home trial.
     *
     * Both pick the newest waiting photos, so a trial tapped mid-run used to
     * encode the same photo the run was on - two full-size decodes on a
     * phone sized for one, one note of which file was in progress for two
     * encodes, and two copies written to the same name. One encode at a time.
     * Nothing taken while holding it takes another lock.
     */
    val stage = Mutex()
}
