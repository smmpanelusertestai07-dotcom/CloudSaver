package com.pocketide.sync

import com.pocketide.model.Lease
import com.pocketide.model.VaultIndex

/**
 * One phone at a time (§5.4). A holder that is working renews its lease as it syncs; an idle one
 * lets it lapse and takes it again with its next real write. Another phone may take an expired
 * lease freely, or an unexpired one only when the owner says "Use here?".
 */
internal object LeasePolicy {
    const val TTL_MS = 45 * 60_000L

    /** Renew when less than this is left, so a working phone writes the index about twice an hour. */
    const val RENEW_MARGIN_MS = 20 * 60_000L

    /** Another phone's lease that has not expired, or null. */
    fun heldByOther(index: VaultIndex?, me: DeviceIdentity, now: Long): Lease? =
        index?.lease?.takeIf { it.deviceId != me.id && it.expiresAt > now }

    fun lease(me: DeviceIdentity, now: Long) = Lease(me.id, me.name, heartbeatAt = now, expiresAt = now + TTL_MS)

    fun needsRenewal(index: VaultIndex?, me: DeviceIdentity, now: Long): Boolean {
        val lease = index?.lease ?: return true
        return lease.deviceId != me.id || lease.expiresAt - now < RENEW_MARGIN_MS
    }
}
