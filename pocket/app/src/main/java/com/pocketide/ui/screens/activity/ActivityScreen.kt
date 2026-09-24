package com.pocketide.ui.screens.activity

import androidx.compose.runtime.Composable
import com.pocketide.ui.components.PendingScreen
import com.pocketide.ui.nav.PocketNav

@Composable
fun ActivityScreen(nav: PocketNav) = PendingScreen("Activity").also { nav.hashCode() }

