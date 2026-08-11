package com.poweruserhub.app.model

data class SettingLock(
    val id: Int? = null,
    val key: String,
    val namespace: String, // "SYSTEM", "SECURE", "GLOBAL"
    val desiredValue: String,
    val isEnabled: Boolean = true,
    val lastVerified: String? = null,
    val status: String? = null // "Verified", "Restored", "Failed"
)
