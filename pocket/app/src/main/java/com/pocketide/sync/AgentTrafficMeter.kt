package com.pocketide.sync

import android.net.TrafficStats
import android.os.Process

/**
 * Counts the agents' own mobile data (§6.7: counted, never blocked). The rooms' Linux processes
 * run under this app's user id, so Android's per-app byte counter covers them; what PocketIDE
 * recorded for its own transfers in the same interval is taken off, and the rest is the agents'.
 * Sampled while rooms run; a sample on mobile data records the interval since the last one.
 */
internal class AgentTrafficMeter(
    private val budget: MeteredDataBudget,
    private val network: NetworkProbe,
    /** Bytes this app sent and received since boot, or a negative number when Android does not say. */
    private val appBytes: () -> Long = ::uidBytes,
) {
    private class Mark(val app: Long, val own: Long)

    private var last: Mark? = null

    /** Records the agents' share of the traffic since the last sample, when on mobile data. */
    @Synchronized
    fun sample() {
        val app = appBytes()
        val now = Mark(app, budget.ownBytesSoFar())
        val before = last
        last = now.takeIf { app >= 0 }
        if (before == null || app < before.app) return
        val agents = (now.app - before.app) - (now.own - before.own)
        if (agents > 0 && network.metered()) budget.record(agents, MeteredDataBudget.KIND_AGENT_TRAFFIC)
    }

    /** The rooms stopped: traffic until the next start is not theirs. */
    @Synchronized
    fun stop() {
        last = null
    }

    private companion object {
        fun uidBytes(): Long {
            val uid = Process.myUid()
            val rx = TrafficStats.getUidRxBytes(uid)
            val tx = TrafficStats.getUidTxBytes(uid)
            return if (rx < 0 || tx < 0) -1 else rx + tx
        }
    }
}
