// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.lucidreamer.LucidDreamerApp
import com.lucidreamer.core.Notifications
import com.lucidreamer.ui.onboarding.OnboardingScreen
import com.lucidreamer.ui.theme.LucidDreamerTheme
import com.lucidreamer.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    companion object {
        /** Deep-link target, set by notification actions. */
        const val EXTRA_ROUTE = "route"

        const val ROUTE_QUICK_DREAM = "quick_dream"
        const val ROUTE_REALITY_CHECKS = "reality_checks"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as LucidDreamerApp).container
        Notifications.ensureChannels(this)

        val requestedRoute = intent?.getStringExtra(EXTRA_ROUTE)

        setContent {
            val themeMode by container.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val dynamic by container.settings.dynamicColour.collectAsState(initial = false)
            val onboardingDone by container.settings.onboardingComplete.collectAsState(initial = true)

            LucidDreamerTheme(themeMode = themeMode, dynamicColor = dynamic) {
                NotificationPermissionRequest()

                if (!onboardingDone) {
                    OnboardingScreen()
                } else {
                    LucidApp(startRoute = startRouteFor(requestedRoute))
                }
            }
        }
    }

    private fun startRouteFor(requested: String?): String = when (requested) {
        ROUTE_QUICK_DREAM -> Routes.dreamEditor(0L)
        ROUTE_REALITY_CHECKS -> Routes.REALITY_CHECKS
        else -> Routes.DASHBOARD
    }
}

/**
 * Asks for notification permission once the app is open.
 *
 * Deliberately not part of a wall of prompts at first launch - it is requested
 * here because the session notification is genuinely needed for the app to work
 * overnight, and the dashboard explains what happens if it is refused.
 */
@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Refusal is handled by the dashboard's warnings rather than a dialog. */ }

    LaunchedEffect(Unit) {
        launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}
