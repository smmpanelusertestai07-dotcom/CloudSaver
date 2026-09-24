package com.pocketide.ui.screens.onboarding

import androidx.compose.runtime.Composable
import com.pocketide.ui.components.PendingScreen

@Composable
fun WelcomeScreen(onContinue: () -> Unit) = PendingScreen("Welcome").also { onContinue.hashCode() }

@Composable
fun GitHubStepScreen(onDone: () -> Unit) = PendingScreen("GitHub").also { onDone.hashCode() }

@Composable
fun DriveStepScreen(onDone: () -> Unit) = PendingScreen("Google Drive").also { onDone.hashCode() }

@Composable
fun ComputerStepScreen(onDone: () -> Unit) = PendingScreen("Computer").also { onDone.hashCode() }

@Composable
fun PrivacyChecklistScreen(onDone: () -> Unit) = PendingScreen("Privacy checklist").also { onDone.hashCode() }

