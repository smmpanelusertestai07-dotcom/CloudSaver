package com.pocketide.ui.screens.schedules

import androidx.compose.runtime.Composable
import com.pocketide.ui.components.PendingScreen
import com.pocketide.ui.nav.PocketNav

@Composable
fun SchedulesScreen(projectId: String?, nav: PocketNav) = PendingScreen("Scheduled tasks").also { nav.hashCode() }

