package com.pocketide.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketide.rooms.RoomState
import com.pocketide.ui.components.Tone
import com.pocketide.ui.screens.project.attempt
import com.pocketide.ui.screens.project.rememberGraph

/**
 * An agent's sign-in in the words its card shows; null when PocketIDE cannot see it. The agent
 * signs in inside its own screen, so "Not signed in" also covers one never signed in yet.
 */
fun signInLabel(signedIn: Boolean?): Pair<String, Tone>? = when (signedIn) {
    true -> "Signed in" to Tone.OK
    false -> "Not signed in" to Tone.WARN
    null -> null
}

/**
 * Whether each agent is signed in, looked at again when a room starts or stops and when the owner
 * comes back to the app (a sign-in happens inside the agent's own screen).
 */
@Composable
internal fun rememberSignIns(agentIds: List<String>, rooms: Map<String, RoomState>): Map<String, Boolean?> {
    val graph = rememberGraph()
    var signIns by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
    var looks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { looks++ }
    val roomKinds = rooms.mapValues { it.value::class }
    LaunchedEffect(agentIds, roomKinds, looks) {
        signIns = agentIds.associateWith { id -> attempt { graph.rooms.signedIn(id) }.getOrNull() }
    }
    return signIns
}
