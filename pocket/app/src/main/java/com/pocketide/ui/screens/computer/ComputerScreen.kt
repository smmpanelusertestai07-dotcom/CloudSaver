package com.pocketide.ui.screens.computer

import androidx.compose.runtime.Composable
import com.pocketide.ui.components.PendingScreen
import com.pocketide.ui.nav.PocketNav

@Composable
fun ComputerScreen(nav: PocketNav) = PendingScreen("Computer").also { nav.hashCode() }

