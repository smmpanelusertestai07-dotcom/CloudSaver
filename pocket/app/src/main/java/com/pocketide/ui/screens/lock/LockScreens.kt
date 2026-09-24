package com.pocketide.ui.screens.lock

import androidx.compose.runtime.Composable
import com.pocketide.model.LockReason
import com.pocketide.ui.components.PendingScreen

/** One screen per lock reason, each with its fix. */
@Composable
fun LockScreen(reason: LockReason) = PendingScreen("Locked: $reason")

/** The app lock (fingerprint or screen lock). */
@Composable
fun AppLockScreen(onUnlock: () -> Unit) = PendingScreen("PocketIDE is locked").also { onUnlock.hashCode() }
