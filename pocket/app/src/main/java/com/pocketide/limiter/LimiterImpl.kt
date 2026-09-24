package com.pocketide.limiter

import android.content.Context
import com.pocketide.core.Clock
import com.pocketide.core.SettingsStore
import com.pocketide.model.Decision
import com.pocketide.model.Guard
import com.pocketide.model.PhoneSnapshot
import com.pocketide.model.Thermal
import com.pocketide.rooms.RoomState
import com.pocketide.rooms.Rooms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The Android side of the limiter: facts, notices, the engine service and settings pages. */
internal interface LimiterHost {
    val vendor: Vendor
    fun phoneFacts(): PhoneFacts
    /** Called whenever the set of running rooms changes: keeps the foreground service and the trace in step. */
    fun roomsRunning(agentIds: Set<String>)
    /** Why the rooms stopped when an earlier process ended, if they were running then and the owner has not dismissed it. */
    fun lastExit(): RoomStop?
    fun forgetExit()
    /** A notification that says what stopped and why (safe stop). */
    fun notifyStopped(stop: RoomStop)
    fun openFix(context: Context, conditionId: String): Boolean
}

/** The agents as the limiter sees them: what kind of room each needs, and its name. */
internal interface AgentKinds {
    fun kind(agentId: String): RoomKind
    fun name(agentId: String): String
}

/**
 * §11 in action: reads the phone, keeps the guard, and reacts. A pause waits for each room's
 * current step (a busy room is never closed); a safe stop gives busy rooms a short grace, then
 * stops everything; low memory closes the longest-idle room; idle sleep closes rooms nobody
 * uses. Every stop syncs first and leaves a banner that says why and that nothing was lost.
 */
internal class LimiterImpl(
    private val phone: PhoneMonitor,
    private val settings: SettingsStore,
    private val rooms: () -> Rooms,
    /** Asks the sync engine for a pass soon; never blocks. */
    private val sync: (reason: String) -> Unit,
    private val agents: AgentKinds,
    private val host: LimiterHost,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val idleSleepMinutes: () -> Int = { IDLE_SLEEP_DEFAULT_MINUTES },
) : Limiter {
    private val guardFlow = MutableStateFlow(Guard.OK)
    override val guard: StateFlow<Guard> = guardFlow

    private val conditionsFlow = MutableStateFlow<List<Condition>>(emptyList())
    override val conditions: StateFlow<List<Condition>> = conditionsFlow

    private val workFlow = MutableStateFlow<Map<String, RoomWork>>(emptyMap())
    override val work: StateFlow<Map<String, RoomWork>> = workFlow

    private val stopFlow = MutableStateFlow<RoomStop?>(null)
    override val lastStop: StateFlow<RoomStop?> = stopFlow

    private val tracker = WorkTracker(clock::now)
    private val reacting = Mutex()
    private var lastRunning: Set<String>? = null
    private var previousGuard = Guard.OK
    private var safeStopSince: Long? = null
    private var safeStopNotified = false
    private var lastMemoryStop = 0L
    private val kicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Starts reading the phone and reacting. Rooms are reached only from here on, never while the graph builds this. */
    fun start() {
        phone.start()
        scope.launch { runCatching { host.lastExit() }.getOrNull()?.let { stop -> stopFlow.compareAndSet(null, stop) } }
        scope.launch {
            val snapshots = phone.snapshot.map { }
            // Rooms that fail to come up are simply "none running"; the limiter keeps guarding.
            val states = flow { emitAll(rooms().states) }.map { }.catch { }
            val settingsChanges = settings.settings.map { }
            val ticks = flow {
                while (true) {
                    emit(Unit)
                    delay(TICK_MS)
                }
            }
            merge(snapshots, states, settingsChanges, ticks, kicks).conflate().collect {
                try {
                    evaluate()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failed pass (a room that would not stop) is tried again on the next tick.
                }
            }
        }
    }

    override fun canStartAgent(agentId: String): Decision {
        val running = runningKinds()
        val snapshot = phone.snapshot.value
        val max = maxAgents()
        return GuardPolicy.canStart(agentId, agents.kind(agentId), running, verdict(snapshot, running, max), snapshot, max, agents::name)
    }

    override fun canStartHeavyWork(what: String): Decision {
        val running = runningKinds()
        val snapshot = phone.snapshot.value
        return GuardPolicy.canStartHeavy(what, verdict(snapshot, running, maxAgents()), snapshot)
    }

    override fun maxAgents(): Int = GuardPolicy.maxAgents(phone.snapshot.value.totalRamBytes, settings.settings.value.maxAgents)

    override fun unsupportedReason(): String? = Requirements.unsupportedReason(host.phoneFacts())

    override fun dismissStop() {
        stopFlow.value = null
        runCatching { host.forgetExit() }
    }

    override fun setBusy(agentId: String, what: String, busy: Boolean) {
        synchronized(tracker) { tracker.setBusy(agentId, what, busy) }
        publishWork(runningKinds().keys)
        // A pause or safe stop waiting for this step can go ahead now.
        if (!busy) kicks.tryEmit(Unit)
    }

    override fun touch(agentId: String) {
        synchronized(tracker) { tracker.touch(agentId) }
        publishWork(runningKinds().keys)
    }

    override suspend fun makeRoomFor(agentId: String): Decision {
        val first = canStartAgent(agentId)
        if (first.allowed) return first
        val running = runningKinds()
        val idle = synchronized(tracker) { tracker.idleLongest(running.keys, minIdleMs = 0) }
        val closing = GuardPolicy.roomsToClose(agentId, agents.kind(agentId), running, idle, phone.snapshot.value, maxAgents())
        if (closing.isNullOrEmpty()) return first
        stopRooms(closing)
        record(RoomStop(closing, StopCause.MEMORY, StopWords.madeRoom(names(closing), agents.name(agentId)), clock.now()))
        return Decision.YES
    }

    override fun openFix(context: Context, conditionId: String): Boolean {
        val opened = host.openFix(context, conditionId)
        // Android cannot read the maker's own switches, so opening the page is the step.
        if (opened && conditionId == ConditionRules.OEM_BATTERY) markOemStepDone()
        return opened
    }

    override fun markOemStepDone() = settings.update { it.copy(oemStepDone = true) }

    private suspend fun evaluate() = reacting.withLock {
        val snapshot = phone.snapshot.value
        val running = runningKinds()
        followRooms(running.keys)
        val verdict = verdict(snapshot, running, maxAgents())
        guardFlow.value = verdict.guard
        conditionsFlow.value = ConditionRules.conditions(snapshot, host.vendor, settings.settings.value.oemStepDone)
        react(verdict, snapshot, running.keys)
        publishWork(runningKinds().keys)
        previousGuard = verdict.guard
    }

    private suspend fun react(verdict: GuardVerdict, snapshot: PhoneSnapshot, running: Set<String>) {
        // Sync first, so the work of the step that is finishing is on its way to Drive.
        if (verdict.guard > previousGuard && verdict.guard >= Guard.PAUSE) requestSync("Pausing agents")
        if (verdict.guard != Guard.SAFE_STOP) {
            safeStopSince = null
            safeStopNotified = false
        }
        if (running.isEmpty()) return
        val heat = snapshot.thermal >= Thermal.SEVERE
        when {
            verdict.guard == Guard.SAFE_STOP -> safeStop(snapshot, heat, running)
            verdict.guard == Guard.PAUSE -> {
                val idle = running.filterNot(::busy)
                if (idle.isEmpty()) return
                stopRooms(idle)
                record(RoomStop(idle, if (heat) StopCause.HEAT else StopCause.BATTERY, StopWords.paused(names(idle), snapshot, heat), clock.now()))
            }
            snapshot.lowMemory -> closeForMemory(running)
            else -> sleepIdle(running)
        }
    }

    /** Busy rooms get [SAFE_STOP_GRACE_MS] to finish the write they are in; then everything stops. */
    private suspend fun safeStop(snapshot: PhoneSnapshot, heat: Boolean, running: Set<String>) {
        val since = safeStopSince ?: clock.now().also { safeStopSince = it }
        if (running.any(::busy) && clock.now() - since < SAFE_STOP_GRACE_MS) return
        try {
            rooms().stopAll()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Whatever did not stop is tried again on the next tick.
        }
        requestSync("Agents stopped safely")
        if (safeStopNotified) return
        safeStopNotified = true
        val stop = RoomStop(running.toList(), if (heat) StopCause.HEAT else StopCause.BATTERY, StopWords.safeStop(names(running), snapshot, heat), clock.now())
        record(stop)
        host.notifyStopped(stop)
    }

    private suspend fun closeForMemory(running: Set<String>) {
        if (clock.now() - lastMemoryStop < MEMORY_STOP_EVERY_MS) return
        val id = synchronized(tracker) { tracker.idleLongest(running, MEMORY_IDLE_MS) }.firstOrNull() ?: return
        lastMemoryStop = clock.now()
        stopRooms(listOf(id))
        record(RoomStop(listOf(id), StopCause.MEMORY, StopWords.memory(agents.name(id)), clock.now()))
    }

    private suspend fun sleepIdle(running: Set<String>) {
        val minutes = idleSleepMinutes()
        val sleepy = synchronized(tracker) { tracker.sleepy(running, minutes * 60_000L) }
        if (sleepy.isEmpty()) return
        stopRooms(sleepy)
        record(RoomStop(sleepy, StopCause.IDLE, StopWords.idle(names(sleepy), minutes), clock.now()))
    }

    /** Stops each room, then asks for a sync so whatever they wrote last goes to Drive. */
    private suspend fun stopRooms(agentIds: List<String>) {
        agentIds.forEach { stopRoom(it) }
        requestSync("Agents stopped")
    }

    private fun names(agentIds: Collection<String>) = agentIds.map(agents::name)

    private suspend fun stopRoom(agentId: String) {
        try {
            rooms().stop(agentId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The room reports its own failure; the limiter moves on to the next one.
        }
    }

    private fun followRooms(running: Set<String>) {
        synchronized(tracker) { tracker.follow(running) }
        if (running != lastRunning) {
            lastRunning = running
            host.roomsRunning(running)
        }
    }

    private fun publishWork(running: Set<String>) {
        workFlow.value = synchronized(tracker) { tracker.work(running, idleSleepMinutes() * 60_000L) }
    }

    private fun busy(agentId: String) = synchronized(tracker) { tracker.isBusy(agentId) }

    private fun record(stop: RoomStop) {
        stopFlow.value = stop
    }

    private fun requestSync(reason: String) {
        runCatching { sync(reason) }
    }

    private fun verdict(snapshot: PhoneSnapshot, running: Map<String, RoomKind>, max: Int): GuardVerdict =
        GuardPolicy.evaluate(snapshot, GuardPolicy.nextRoom(running.values, max))

    /** Rooms that are starting or running, with their kind. */
    private fun runningKinds(): Map<String, RoomKind> {
        val states = runCatching { rooms().states.value }.getOrDefault(emptyMap())
        return states.filterValues { it is RoomState.Running || it is RoomState.Starting }.mapValues { agents.kind(it.key) }
    }

    companion object {
        const val IDLE_SLEEP_DEFAULT_MINUTES = 30
        private const val TICK_MS = 30_000L
        private const val SAFE_STOP_GRACE_MS = 60_000L
        private const val MEMORY_IDLE_MS = 2 * 60_000L
        private const val MEMORY_STOP_EVERY_MS = 60_000L
    }
}

/** The banner sentences for each kind of stop. Every one ends in "Nothing was lost." */
internal object StopWords {
    fun idle(names: List<String>, minutes: Int) =
        "${list(names)} went to sleep after $minutes minutes without work. Nothing was lost."

    fun memory(name: String) =
        "$name was closed to free memory; it had no work running. Nothing was lost."

    fun madeRoom(names: List<String>, forName: String) =
        "${list(names)} closed to make room for $forName; nothing was running there. Nothing was lost."

    fun paused(names: List<String>, snapshot: PhoneSnapshot, heat: Boolean) =
        "${list(names)} paused after the current step because ${because(snapshot, heat)}. ${reopen(heat)} Nothing was lost."

    fun safeStop(names: List<String>, snapshot: PhoneSnapshot, heat: Boolean) =
        "${list(names)} stopped safely because ${because(snapshot, heat)}. ${reopen(heat)} Nothing was lost."

    private fun because(snapshot: PhoneSnapshot, heat: Boolean) =
        if (heat) "the phone is too hot" else "the battery is at ${snapshot.batteryPercent} %"

    private fun reopen(heat: Boolean) =
        if (heat) "Open it again once the phone cools down." else "Open it again once the phone charges."

    private fun list(names: List<String>): String = when (names.size) {
        0 -> "The agents"
        1 -> names.single()
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}
