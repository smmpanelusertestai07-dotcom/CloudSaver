package com.pocketide.ui.screens.agents

import androidx.compose.runtime.Composable
import com.pocketide.ui.components.PendingScreen
import com.pocketide.ui.nav.PocketNav

@Composable
fun MoreAgentsScreen(nav: PocketNav) = PendingScreen("More agents").also { nav.hashCode() }

