package com.pocketide.ui.screens.home

import androidx.compose.runtime.Composable
import com.pocketide.ui.components.PendingScreen
import com.pocketide.ui.nav.PocketNav

@Composable
fun HomeScreen(nav: PocketNav) = PendingScreen("Home").also { nav.hashCode() }

