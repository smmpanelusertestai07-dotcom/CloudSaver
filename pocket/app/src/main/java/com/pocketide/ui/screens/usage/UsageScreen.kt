package com.pocketide.ui.screens.usage

import androidx.compose.runtime.Composable
import com.pocketide.ui.components.PendingScreen
import com.pocketide.ui.nav.PocketNav

@Composable
fun UsageScreen(nav: PocketNav) = PendingScreen("Usage").also { nav.hashCode() }

