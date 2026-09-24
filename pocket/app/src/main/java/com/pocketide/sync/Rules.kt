package com.pocketide.sync

import com.pocketide.model.SessionRecord
import com.pocketide.model.VaultIndex
import java.time.Instant
import java.time.ZoneOffset

/** Storage limits (§6.5). */
internal object Limits {
    const val GIB = 1L shl 30
    /** The phone always keeps at least this much free, whatever PocketIDE's own limit says. */
    const val MIN_FREE = 2 * GIB
    const val NOTICE_AT = 0.8
    const val CLEAN_AT = 0.9
    /** Waiting for Drive locks the app after a day, or once this much is waiting. */
    const val WAIT_LOCK_MS = 24 * Durations.HOUR
    const val WAIT_LOCK_BYTES = 200 * MeteredDataBudget.MB

    fun gb(value: Int): Long = value.coerceAtLeast(0) * GIB

    /** How full PocketIDE's share of the phone is, counting the 2 GB that must stay free. */
    fun phoneSpace(appBytes: Long, freeBytes: Long, limitGb: Int): PhoneSpace {
        if (appBytes <= 0) return PhoneSpace.OK
        val limit = minOf(gb(limitGb), appBytes + (freeBytes - MIN_FREE).coerceAtLeast(0))
        if (limit <= 0) return PhoneSpace.FULL
        val used = appBytes.toDouble() / limit
        return when {
            used >= CLEAN_AT -> PhoneSpace.FULL
            used >= NOTICE_AT -> PhoneSpace.NEARLY_FULL
            else -> PhoneSpace.OK
        }
    }

    fun locks(waiting: WaitingMark, pendingBytes: Long, now: Long): Boolean =
        now - waiting.since >= WAIT_LOCK_MS || pendingBytes >= WAIT_LOCK_BYTES
}

/** Retention (§6.4, §6.6, §6.8): Recently deleted for 30 days, and moves that come with a notice. */
internal object Retention {
    const val RECENTLY_DELETED_DAYS = 30
    const val NOTICE_DAYS = 7
    const val TRIM_MONTHS = 12
    const val RULE_KEEP = "keep"
    const val RULE_TRIM = "trim"

    /** Recently deleted for 30 days or more, counted from the date stored in Drive. */
    fun dueForErase(index: VaultIndex, now: Long): Set<String> = index.sessions
        .filter { s -> s.deletedAt?.let { now - it >= Durations.days(RECENTLY_DELETED_DAYS) } == true }
        .map { it.id }
        .toSet()

    fun monthsBefore(now: Long, months: Int): Long =
        Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).minusMonths(months.toLong()).toInstant().toEpochMilli()

    data class Plan(
        val notices: Map<String, RetentionNotice>,
        /** Sessions that just got their 7-day notice. */
        val noticed: List<String>,
        /** Sessions whose notice ran out: they move to Recently deleted now. */
        val moveNow: List<String>,
    )

    /**
     * Chats whose last message is older than [months] (0 = never) get a 7-day notice first, then
     * move to Recently deleted. A chat that was used again in the meantime keeps its place.
     */
    fun plan(sessions: Collection<SessionRecord>, notices: Map<String, RetentionNotice>, rule: String, months: Int, now: Long): Plan {
        val eligible = if (months <= 0) emptySet() else {
            val cutoff = monthsBefore(now, months)
            sessions.filter { it.deletedAt == null && it.lastActivityAt < cutoff }.map { it.id }.toSet()
        }
        val kept = notices.filter { (id, n) -> n.rule != rule || id in eligible }
        val noticed = eligible.filter { it !in kept }.sorted()
        val all = kept + noticed.associateWith { RetentionNotice(rule, now, now + Durations.days(NOTICE_DAYS)) }
        val moveNow = all.filter { (id, n) -> n.rule == rule && id in eligible && now >= n.dueAt }.keys.sorted()
        return Plan(all - moveNow.toSet(), noticed, moveNow)
    }
}
