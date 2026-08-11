package com.poweruserhub.app.model

data class AppItem(
    val packageName: String,
    val appName: String,
    val isSystem: Boolean,
    val isEnabled: Boolean,
    val versionName: String,
    val standbyBucket: String = "unknown",
    val batteryExempted: Boolean = false,
    val backgroundRestricted: Boolean = false,
    val permissions: List<String> = emptyList(),
    val activities: List<String> = emptyList(),
    val services: List<String> = emptyList(),
    val receivers: List<String> = emptyList(),
    val providers: List<String> = emptyList()
)
