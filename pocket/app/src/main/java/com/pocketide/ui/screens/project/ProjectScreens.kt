package com.pocketide.ui.screens.project

import androidx.compose.runtime.Composable
import com.pocketide.ui.components.PendingScreen
import com.pocketide.ui.nav.PocketNav

@Composable
fun ProjectScreen(projectId: String, nav: PocketNav) = PendingScreen("Project $projectId").also { nav.hashCode() }

@Composable
fun AgentScreen(sessionId: String, nav: PocketNav) = PendingScreen("Agent $sessionId").also { nav.hashCode() }

