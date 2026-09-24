package com.pocketide.ui.screens.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketide.ui.nav.PocketNav
import com.pocketide.ui.web.TerminalState
import com.pocketide.ui.web.TerminalView
import kotlinx.coroutines.launch

/** The `>_` tab: a shell in this session's room, in its worktree, with the keyboard bar. */
@Composable
fun TerminalPanel(sessionId: String, state: TerminalState, nav: PocketNav, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var problem by remember(state) { mutableStateOf<String?>(null) }
    var tries by remember(state) { mutableIntStateOf(0) }

    LaunchedEffect(state, tries) {
        if (state.url != null) return@LaunchedEffect
        problem = null
        attempt { graph.rooms.terminal(sessionId) }
            .onSuccess { state.url = it.url }
            .onFailure { problem = plainReason(it) }
    }

    val url = state.url
    when {
        url != null -> TerminalView(
            url = url,
            state = state,
            isInternal = graph.portBridge::isInternal,
            onOpenExternal = nav::openExternal,
            onNotice = { message -> scope.launch { snackbar.showSnackbar(message) } },
            modifier = modifier,
        )
        problem != null -> Column(
            modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("The terminal did not open: $problem", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Button(onClick = { tries++ }) { Text("Try again") }
        }
        else -> Column(
            modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyState(Icons.Filled.Terminal, "Opening a shell", "A shell in this session's room, in its own worktree.")
            CircularProgressIndicator()
        }
    }
}
