package com.poweruserhub.app.model

data class SettingItem(
    val key: String,
    val value: String,
    val namespace: String // "SYSTEM", "SECURE", "GLOBAL"
)
