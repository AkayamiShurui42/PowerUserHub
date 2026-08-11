package com.poweruserhub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.poweruserhub.app.service.LockDatabaseHelper
import com.poweruserhub.app.service.ShellService
import com.poweruserhub.app.ui.Screen
import com.poweruserhub.app.ui.screens.*
import com.poweruserhub.app.ui.theme.PowerUserHubTheme
import com.poweruserhub.app.worker.LockEnforcementWorker
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shellService = ShellService(applicationContext)
        val dbHelper = LockDatabaseHelper(applicationContext)

        // Schedule periodic background enforcement
        scheduleEnforcementWork()

        setContent {
            PowerUserHubTheme(darkTheme = true, dynamicColor = false) { // Enforce premium dark slate theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(shellService, dbHelper)
                }
            }
        }
    }

    private fun scheduleEnforcementWork() {
        val workRequest = PeriodicWorkRequestBuilder<LockEnforcementWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "SettingLockEnforcement",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(shellService: ShellService, dbHelper: LockDatabaseHelper) {
    val navController = rememberNavController()
    var currentTitle by remember { mutableStateOf("Overview") }

    val navigationItems = listOf(
        Triple(Screen.Dashboard, Icons.Default.Home, "Overview"),
        Triple(Screen.Settings, Icons.Default.Settings, "Settings"),
        Triple(Screen.Apps, Icons.Default.Apps, "Apps"),
        Triple(Screen.Locks, Icons.Default.Lock, "Locks"),
        Triple(Screen.Services, Icons.Default.PlayArrow, "Services"),
        Triple(Screen.Developer, Icons.Default.Build, "Dev Mode")
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        text = currentTitle, 
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    ) 
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                navigationItems.forEach { (screen, icon, label) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 10.sp) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            if (currentRoute != screen.route) {
                                currentTitle = screen.title
                                navController.navigate(screen.route) {
                                    popUpTo(Screen.Dashboard.route) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    shellService = shellService,
                    onNavigateToSettings = {
                        currentTitle = Screen.Settings.title
                        navController.navigate(Screen.Settings.route)
                    },
                    onNavigateToApps = {
                        currentTitle = Screen.Apps.title
                        navController.navigate(Screen.Apps.route)
                    },
                    onNavigateToLocks = {
                        currentTitle = Screen.Locks.title
                        navController.navigate(Screen.Locks.route)
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsExplorerScreen(shellService, dbHelper)
            }
            composable(Screen.Apps.route) {
                AppExplorerScreen(shellService)
            }
            composable(Screen.Locks.route) {
                LockedSettingsScreen(dbHelper, shellService)
            }
            composable(Screen.Services.route) {
                ServiceMonitorScreen(shellService)
            }
            composable(Screen.Developer.route) {
                AdvancedDeveloperModeScreen(shellService)
            }
        }
    }
}
