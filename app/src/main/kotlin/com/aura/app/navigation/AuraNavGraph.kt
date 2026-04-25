package com.aura.app.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aura.core.ui.theme.AuraVoid
import com.aura.feature.chat.ChatScreen
import com.aura.feature.goals.GoalsScreen
import com.aura.feature.timeline.TimelineScreen

sealed class AuraRoute(val route: String, val label: String, val icon: @Composable () -> Unit) {
    data object Chat : AuraRoute("chat", "Chat", { Icon(Icons.Default.Chat, "Chat") })
    data object Timeline : AuraRoute("timeline", "Activity", { Icon(Icons.Default.Timeline, "Activity") })
    data object Goals : AuraRoute("goals", "Goals", { Icon(Icons.Default.Star, "Goals") })
}

private val bottomNavItems = listOf(AuraRoute.Chat, AuraRoute.Timeline, AuraRoute.Goals)

@Composable
fun AuraNavGraph() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AuraVoid,
        bottomBar = {
            NavigationBar(containerColor = AuraVoid) {
                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        icon = screen.icon,
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AuraRoute.Chat.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(AuraRoute.Chat.route) { ChatScreen() }
            composable(AuraRoute.Timeline.route) { TimelineScreen() }
            composable(AuraRoute.Goals.route) { GoalsScreen() }
        }
    }
}
