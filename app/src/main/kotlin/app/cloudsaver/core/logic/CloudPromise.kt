package app.cloudsaver.core.logic

/**
 * What the app can honestly promise with a given cloud app.
 *
 * Confirmation quality is not the same everywhere, and pretending it is would
 * be the one lie that matters: it is what decides whether an original is ever
 * offered for deletion. Each cloud gets the line its own abilities support,
 * including the one we cannot inspect at all.
 */
object CloudPromise {

    enum class Promise {
        /** Removes uploads and de-duplicates: confirmation is exact. */
        EXACT,
        /** No duplicate check, but the ledger stops us re-sending. */
        LEDGER_ONLY,
        /** Nothing detectable; size and time are all there is. */
        UNKNOWN
    }

    fun forCloud(id: String, caps: CloudCapability.Caps): Promise = when {
        id == "other" -> Promise.UNKNOWN
        caps.hasFreeUpSpace && caps.hasHashDedupe -> Promise.EXACT
        else -> Promise.LEDGER_ONLY
    }
}
