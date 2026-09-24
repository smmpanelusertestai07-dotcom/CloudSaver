package com.pocketide.ui.screens.chats

import androidx.compose.runtime.Composable
import com.pocketide.ui.components.PendingScreen
import com.pocketide.ui.nav.PocketNav

@Composable
fun ChatsScreen(nav: PocketNav) = PendingScreen("Chats").also { nav.hashCode() }

@Composable
fun TranscriptScreen(sessionId: String, nav: PocketNav) = PendingScreen("Chat $sessionId").also { nav.hashCode() }

@Composable
fun RecentlyDeletedScreen(nav: PocketNav) = PendingScreen("Recently deleted").also { nav.hashCode() }

@Composable
fun WaitingUploadsScreen(nav: PocketNav) = PendingScreen("Waiting to upload").also { nav.hashCode() }

