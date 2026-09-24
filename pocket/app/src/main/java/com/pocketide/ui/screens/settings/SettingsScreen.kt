package com.pocketide.ui.screens.settings

import androidx.compose.runtime.Composable
import com.pocketide.ui.components.PendingScreen
import com.pocketide.ui.nav.PocketNav

@Composable
fun SettingsScreen(nav: PocketNav) = PendingScreen("Settings").also { nav.hashCode() }

