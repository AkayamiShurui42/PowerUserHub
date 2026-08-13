package com.poweruserhub.app.ui

sealed class Screen(val route: String, val title: String) {
    object Dashboard : Screen("dashboard", "Overview")
    object Settings : Screen("settings", "Settings Explorer")
    object Apps : Screen("apps", "App Explorer")
    object Locks : Screen("locks", "Locked Settings")
    object Services : Screen("services", "Component Explorer")
    object Developer : Screen("developer", "Developer Mode")
}
