// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lucidreamer.ui.checks.RealityCheckScreen
import com.lucidreamer.ui.cues.CueProfileListScreen
import com.lucidreamer.ui.cues.CueRuleEditorScreen
import com.lucidreamer.ui.dashboard.DashboardScreen
import com.lucidreamer.ui.dev.DeveloperScreen
import com.lucidreamer.ui.experiments.ExperimentsScreen
import com.lucidreamer.ui.journal.DreamEditorScreen
import com.lucidreamer.ui.journal.JournalScreen
import com.lucidreamer.ui.reliability.ReliabilityScreen
import com.lucidreamer.ui.schedule.ScheduleScreen
import com.lucidreamer.ui.sensing.SensingScreen
import com.lucidreamer.ui.settings.PrivacyScreen
import com.lucidreamer.ui.settings.SettingsScreen
import com.lucidreamer.ui.settings.WbtbScreen
import com.lucidreamer.ui.stats.StatsScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val JOURNAL = "journal"
    const val CUES = "cues"
    const val STATS = "stats"
    const val SETTINGS = "settings"

    const val DREAM_EDITOR = "dream/{dreamId}"
    fun dreamEditor(id: Long) = "dream/$id"

    const val CUE_EDITOR = "cue_profile/{profileId}"
    fun cueEditor(id: Long) = "cue_profile/$id"

    const val SCHEDULE = "schedule"
    const val REALITY_CHECKS = "reality_checks"
    const val WBTB = "wbtb"
    const val PRIVACY = "privacy"
    const val RELIABILITY = "reliability"
    const val DEVELOPER = "developer"
    const val SENSING = "sensing"
    const val EXPERIMENTS = "experiments"
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Routes.DASHBOARD, "Tonight", Icons.Default.Nightlight),
    TabItem(Routes.JOURNAL, "Journal", Icons.Default.Book),
    TabItem(Routes.CUES, "Cues", Icons.Default.MusicNote),
    TabItem(Routes.STATS, "Stats", Icons.Default.Insights),
    TabItem(Routes.SETTINGS, "Settings", Icons.Default.Settings),
)

@Composable
fun LucidApp(startRoute: String = Routes.DASHBOARD) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination

    // The bar is hidden on detail screens so they get the full height; it is
    // only meaningful at the top level.
    val showBottomBar = currentDestination?.route in tabs.map { it.route }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { insets ->
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = Modifier.padding(insets),
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onOpenCues = { navController.navigate(Routes.CUES) },
                    onOpenJournal = { navController.navigate(Routes.JOURNAL) },
                    onOpenSchedule = { navController.navigate(Routes.SCHEDULE) },
                    onOpenReliability = { navController.navigate(Routes.RELIABILITY) },
                )
            }

            composable(Routes.JOURNAL) {
                JournalScreen(
                    onOpenDream = { navController.navigate(Routes.dreamEditor(it)) },
                    onNewDream = { navController.navigate(Routes.dreamEditor(0L)) },
                )
            }

            composable(Routes.DREAM_EDITOR) { entry ->
                val id = entry.arguments?.getString("dreamId")?.toLongOrNull() ?: 0L
                DreamEditorScreen(dreamId = id, onDone = { navController.popBackStack() })
            }

            composable(Routes.CUES) {
                CueProfileListScreen(
                    onEditProfile = { navController.navigate(Routes.cueEditor(it)) },
                )
            }

            composable(Routes.CUE_EDITOR) { entry ->
                val id = entry.arguments?.getString("profileId")?.toLongOrNull() ?: 0L
                CueRuleEditorScreen(profileId = id, onDone = { navController.popBackStack() })
            }

            composable(Routes.STATS) { StatsScreen() }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenSchedule = { navController.navigate(Routes.SCHEDULE) },
                    onOpenCues = { navController.navigate(Routes.CUES) },
                    onOpenWbtb = { navController.navigate(Routes.WBTB) },
                    onOpenRealityChecks = { navController.navigate(Routes.REALITY_CHECKS) },
                    onOpenPrivacy = { navController.navigate(Routes.PRIVACY) },
                    onOpenReliability = { navController.navigate(Routes.RELIABILITY) },
                    onOpenDeveloper = { navController.navigate(Routes.DEVELOPER) },
                    onOpenSensing = { navController.navigate(Routes.SENSING) },
                    onOpenExperiments = { navController.navigate(Routes.EXPERIMENTS) },
                )
            }

            composable(Routes.SCHEDULE) { ScheduleScreen(onDone = { navController.popBackStack() }) }
            composable(Routes.REALITY_CHECKS) { RealityCheckScreen(onDone = { navController.popBackStack() }) }
            composable(Routes.WBTB) { WbtbScreen(onDone = { navController.popBackStack() }) }
            composable(Routes.PRIVACY) { PrivacyScreen(onDone = { navController.popBackStack() }) }
            composable(Routes.RELIABILITY) { ReliabilityScreen(onDone = { navController.popBackStack() }) }
            composable(Routes.DEVELOPER) { DeveloperScreen(onDone = { navController.popBackStack() }) }
            composable(Routes.SENSING) { SensingScreen(onDone = { navController.popBackStack() }) }
            composable(Routes.EXPERIMENTS) { ExperimentsScreen(onDone = { navController.popBackStack() }) }
        }
    }
}

/** Standard bottom-bar behaviour: single instance, state preserved, no back-stack pile-up. */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
