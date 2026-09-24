package com.pocketide.sync

import android.content.SharedPreferences
import com.pocketide.core.Clock
import com.pocketide.core.Settings
import com.pocketide.model.Decision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Byte counters by key. Production keeps them in private preferences. */
internal interface CounterStore {
    fun all(): Map<String, Long>
    fun add(key: String, delta: Long)
    fun remove(keys: Collection<String>)
}

internal class PrefsCounterStore(private val prefs: SharedPreferences) : CounterStore {
    @Synchronized
    override fun all(): Map<String, Long> = prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    @Synchronized
    override fun add(key: String, delta: Long) {
        prefs.edit().putLong(key, prefs.getLong(key, 0) + delta).apply()
    }

    @Synchronized
    override fun remove(keys: Collection<String>) {
        if (keys.isEmpty()) return
        prefs.edit().apply { keys.forEach(::remove) }.apply()
    }
}

/**
 * PocketIDE's own transfers on metered networks, per UTC day and month and by kind (§6.7). Wi-Fi is
 * free and never counted. Agents' own traffic is counted by its callers but never blocked here.
 */
internal class MeteredDataBudget(
    private val settings: () -> Settings,
    private val network: NetworkProbe,
    private val counters: CounterStore,
    private val clock: Clock,
) : DataBudget {
    private val flow = MutableStateFlow(read())
    override val usage: StateFlow<DataUsage> = flow

    override fun allow(bytes: Long, kind: String, big: Boolean): Decision {
        if (!network.metered()) return Decision.YES
        val s = settings()
        if (big && s.wifiOnlyBigDownloads) return Decision.no(WAITS_FOR_WIFI)
        if (s.mobileDailyLimitMb <= 0) return Decision.no(MOBILE_OFF)
        if (today() + bytes > s.mobileDailyLimitMb * MB) return Decision.no(LIMIT_REACHED)
        if (big && network.dataSaverRestricted()) return Decision.no(DATA_SAVER)
        return Decision.YES
    }

    override fun record(bytes: Long, kind: String) {
        if (bytes <= 0 || !network.metered()) return
        val now = clock.now()
        counters.add(dayKey(now, kind), bytes)
        counters.add(monthKey(now, kind), bytes)
        prune(now)
        flow.value = read()
    }

    /** Re-reads the counters (a new day or month started since the last transfer). */
    fun refresh() {
        flow.value = read()
    }

    private fun today(): Long {
        val prefix = "d:${day(clock.now())}:"
        return counters.all().filterKeys { it.startsWith(prefix) }.values.sum()
    }

    private fun read(): DataUsage {
        val now = clock.now()
        val all = counters.all()
        val dayPrefix = "d:${day(now)}:"
        val monthPrefix = "m:${month(now)}:"
        val byType = all.filterKeys { it.startsWith(monthPrefix) }.mapKeys { it.key.removePrefix(monthPrefix) }
        return DataUsage(
            todayMeteredBytes = all.filterKeys { it.startsWith(dayPrefix) }.values.sum(),
            monthMeteredBytes = byType.values.sum(),
            byType = byType,
        )
    }

    /** Keeps this month and the one before; older counters only grow the preferences file. */
    private fun prune(now: Long) {
        val keep = setOf(month(now), month(startOfMonth(now) - 1))
        val stale = counters.all().keys.filter { key ->
            val stamp = key.split(':').getOrNull(1) ?: return@filter true
            stamp.take(7) !in keep
        }
        counters.remove(stale)
    }

    private fun startOfMonth(now: Long): Long =
        Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    companion object {
        const val MB = 1_000_000L
        const val KIND_SYNC = "sync"
        const val KIND_RESTORE = "restore"
        const val KIND_MOVE = "move"
        const val KIND_REENCRYPT = "reencrypt"

        const val WAITS_FOR_WIFI = "Waits for Wi-Fi"
        const val MOBILE_OFF = "PocketIDE's own transfers use Wi-Fi only (Settings → Data)"
        const val LIMIT_REACHED = "Today's mobile data limit is reached"
        const val DATA_SAVER = "Data Saver is on, so big transfers wait for Wi-Fi"

        private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
        private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC)

        fun day(now: Long): String = DAY.format(Instant.ofEpochMilli(now))
        fun month(now: Long): String = MONTH.format(Instant.ofEpochMilli(now))
        fun dayKey(now: Long, kind: String) = "d:${day(now)}:$kind"
        fun monthKey(now: Long, kind: String) = "m:${month(now)}:$kind"
    }
}
