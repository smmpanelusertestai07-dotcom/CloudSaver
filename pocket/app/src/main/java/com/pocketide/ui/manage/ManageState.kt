package com.pocketide.ui.manage

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.pocketide.AppGraph
import com.pocketide.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun rememberGraph(): AppGraph {
    val context = LocalContext.current
    return remember(context) { context.graph }
}

/** The activity behind a Compose context, for Android's own authentication prompt. */
fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current != null) {
        if (current is FragmentActivity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

/** A value fetched from a module, with when it was fetched and what went wrong. */
@Stable
class LoadState<T> internal constructor() {
    var value by mutableStateOf<T?>(null)
        internal set
    var at by mutableStateOf<Long?>(null)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    var loading by mutableStateOf(true)
        internal set
    internal var generation by mutableIntStateOf(0)

    fun refresh() {
        generation++
    }
}

/** Loads once per [key] and again on [LoadState.refresh]; a failure keeps the last good value. */
@Composable
fun <T> rememberLoad(key: Any?, now: () -> Long, block: suspend () -> T): LoadState<T> {
    val state = remember(key) { LoadState<T>() }
    val latest by rememberUpdatedState(block)
    LaunchedEffect(state, state.generation) {
        state.loading = true
        attempt { latest() }
            .onSuccess {
                state.value = it
                state.at = now()
                state.error = null
            }
            .onFailure { state.error = PlainError.of(it) }
        state.loading = false
    }
    return state
}

/**
 * Keys of work that outlives its screen (moving or deleting everything, a reset). Shared by every
 * runner, so leaving the screen and coming back can never start the same work twice.
 */
internal object OutlivingWork {
    var keys by mutableStateOf(emptySet<String>())
        private set

    /** Adds [key] unless it is already running; false when it was. */
    fun claim(key: String): Boolean = synchronized(this) {
        if (key in keys) return false
        keys = keys + key
        true
    }

    fun release(key: String) = synchronized(this) { keys = keys - key }
}

/**
 * Runs the owner's taps: one at a time per key, the result as a short message. Work that must
 * finish even when the owner leaves the screen (moving or deleting everything) runs in the
 * app's scope instead of the screen's. Keys change from the app's threads too, so every change
 * goes through one lock.
 */
@Stable
class ActionRunner internal constructor(private val screenScope: CoroutineScope, private val appScope: CoroutineScope) {
    val snackbar = SnackbarHostState()
    var busy by mutableStateOf(emptySet<String>())
        private set

    fun isBusy(key: String) = key in busy || key in OutlivingWork.keys

    fun <T> run(
        key: String,
        done: String? = null,
        outlivesScreen: Boolean = false,
        onFailure: (Throwable) -> Unit = { say(PlainError.of(it)) },
        onSuccess: (T) -> Unit = {},
        block: suspend () -> T,
    ) {
        if (!claim(key)) return
        if (outlivesScreen && !OutlivingWork.claim(key)) {
            release(key)
            return
        }
        val job = (if (outlivesScreen) appScope else screenScope).launch {
            attempt { block() }
                .onSuccess {
                    onSuccess(it)
                    if (done != null) say(done)
                }
                .onFailure(onFailure)
        }
        // Runs even when the scope was already cancelled and the block never started.
        job.invokeOnCompletion {
            if (outlivesScreen) OutlivingWork.release(key)
            release(key)
        }
    }

    fun say(text: String) {
        screenScope.launch { snackbar.showSnackbar(text) }
    }

    private fun claim(key: String): Boolean = synchronized(this) {
        if (key in busy) return false
        busy = busy + key
        true
    }

    private fun release(key: String) = synchronized(this) { busy = busy - key }
}

@Composable
fun rememberActionRunner(): ActionRunner {
    val scope = rememberCoroutineScope()
    val graph = rememberGraph()
    return remember(scope, graph) { ActionRunner(scope, graph.scope) }
}
