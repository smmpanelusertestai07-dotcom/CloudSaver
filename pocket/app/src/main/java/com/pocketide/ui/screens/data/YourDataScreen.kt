package com.pocketide.ui.screens.data

import androidx.compose.runtime.Composable
import com.pocketide.ui.components.PendingScreen
import com.pocketide.ui.nav.PocketNav

@Composable
fun YourDataScreen(nav: PocketNav) = PendingScreen("Your data").also { nav.hashCode() }

