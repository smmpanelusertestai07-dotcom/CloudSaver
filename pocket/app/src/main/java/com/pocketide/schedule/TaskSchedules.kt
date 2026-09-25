package com.pocketide.schedule

import com.pocketide.core.AppJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Whether the phone is charging and on Wi-Fi right now; a plain reason when not. */
internal fun interface PowerAndWifi {
    fun whyNot(): String?
}

/**
 * Scheduled tasks, kept in a private JSON file and mirrored into WorkManager (one periodic job
 * per enabled task).
 */
internal class TaskSchedules(
    private val file: File,
    private val scheduler: TaskScheduler,
    private val powerAndWifi: PowerAndWifi,
    private val io: CoroutineDispatcher,
    val runner: ScheduledRun,
) : Schedules {

    private val lock = Mutex()

    /** One run of a task at a time, whether periodic or "Run now". */
    private val runs = ConcurrentHashMap<String, Mutex>()
    private val state = MutableStateFlow<List<ScheduledTask>>(emptyList())
    private val loaded = CompletableDeferred<Unit>()

    override val tasks: StateFlow<List<ScheduledTask>> = state.asStateFlow()

    /** Reads the saved tasks and makes sure WorkManager has each enabled one. */
    suspend fun load() {
        lock.withLock {
            if (loaded.isCompleted) return
            state.value = withContext(io) { read() }
            state.value.forEach { scheduler.schedule(it, update = false) }
            loaded.complete(Unit)
        }
    }

    fun find(taskId: String): ScheduledTask? = state.value.firstOrNull { it.id == taskId }

    override suspend fun save(task: ScheduledTask) {
        check(task)
        load()
        lock.withLock {
            val old = state.value.firstOrNull { it.id == task.id }
            // The screens edit a copy: the run history stays the app's own.
            val merged = task.copy(
                lastRunAt = old?.lastRunAt ?: task.lastRunAt,
                lastSessionId = old?.lastSessionId ?: task.lastSessionId,
                runningSessionId = old?.runningSessionId,
                runningSince = old?.runningSince,
            )
            write(state.value.filterNot { it.id == task.id } + merged)
            scheduler.schedule(merged)
        }
    }

    override suspend fun remove(id: String) {
        load()
        lock.withLock {
            scheduler.cancel(id)
            write(state.value.filterNot { it.id == id })
        }
    }

    /**
     * Queues a run of the task and returns the session it will use. While the task already runs,
     * or a "Run now" waits for the charger, that run's session is returned instead: a second
     * request would be dropped, and its session stay empty.
     */
    override suspend fun runNow(id: String): String? {
        load()
        val task = find(id) ?: throw ScheduleException("This task was deleted.")
        if (running(id)) task.runningSessionId?.let { return it }
        scheduler.queuedRun(id)?.let { return it }
        powerAndWifi.whyNot()?.let { throw ScheduleException(it) }
        val session = runner.newSession(task)
        scheduler.runOnce(task.id, session.id)
        return session.id
    }

    /** Runs [block] unless a run of the task is going on already; null then. */
    suspend fun <T> exclusively(taskId: String, block: suspend () -> T): T? {
        val gate = runs.getOrPut(taskId) { Mutex() }
        if (!gate.tryLock()) return null
        return try {
            block()
        } finally {
            gate.unlock()
        }
    }

    private fun running(taskId: String): Boolean = runs[taskId]?.isLocked == true

    /** Called by a run before the agent starts. */
    suspend fun recordStart(taskId: String, at: Long, sessionId: String) =
        change(taskId) { it.copy(runningSessionId = sessionId, runningSince = at) }

    /** Called by a run when it ends. */
    suspend fun recordRun(taskId: String, at: Long, sessionId: String) =
        change(taskId) { it.copy(lastRunAt = at, lastSessionId = sessionId, runningSessionId = null, runningSince = null) }

    private suspend fun change(taskId: String, edit: (ScheduledTask) -> ScheduledTask) {
        load()
        lock.withLock {
            val list = state.value
            if (list.none { it.id == taskId }) return
            write(list.map { if (it.id == taskId) edit(it) else it })
        }
    }

    private fun check(task: ScheduledTask) {
        when {
            task.title.isBlank() -> throw ScheduleException("Give the task a title.")
            task.prompt.isBlank() -> throw ScheduleException("Write what the agent should do.")
            task.everyHours < 1 -> throw ScheduleException("A task runs at most once an hour.")
        }
        runner.refusal(task)?.let { throw ScheduleException(it) }
    }

    private suspend fun write(list: List<ScheduledTask>) {
        val sorted = list.sortedBy { it.id }
        withContext(io) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(AppJson.encodeToString(LIST, sorted))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        state.value = sorted
    }

    private fun read(): List<ScheduledTask> {
        if (!file.isFile) return emptyList()
        return try {
            AppJson.decodeFromString(LIST, file.readText())
        } catch (e: IllegalArgumentException) {
            emptyList()
        } catch (e: IOException) {
            emptyList()
        }
    }

    private companion object {
        val LIST = ListSerializer(ScheduledTask.serializer())
    }
}
