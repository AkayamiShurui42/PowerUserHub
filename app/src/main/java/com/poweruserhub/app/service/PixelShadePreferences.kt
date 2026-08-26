package com.poweruserhub.app.service

import android.content.Context
import com.poweruserhub.app.model.*

object PixelShadePreferences {
    private const val NAME = "pixel_shade"

    fun load(context: Context): PixelShadeConfig {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return PixelShadeConfig(
            enabled = p.getBoolean("enabled", false),
            editMode = p.getBoolean("editMode", false),
            placement = enumValue(p.getString("placement", null), TriggerPlacement.OVER_STATUS_BAR),
            visualHeightDp = p.getFloat("visualHeightDp", 2f),
            detectionHeightDp = p.getFloat("detectionHeightDp", 12f),
            widthPercent = p.getFloat("widthPercent", 100f),
            verticalSwipeThresholdDp = p.getFloat("verticalSwipeThresholdDp", 40f),
            horizontalSwipeThresholdDp = p.getFloat("horizontalSwipeThresholdDp", 24f),
            swipeDownAction = enumValue(p.getString("swipeDownAction", null), TriggerAction.OPEN_SHADE),
            tapEnabled = p.getBoolean("tapEnabled", false),
            tapAction = enumValue(p.getString("tapAction", null), TriggerAction.OPEN_SHADE),
            brightnessSwipeEnabled = p.getBoolean("brightnessSwipeEnabled", true),
            brightnessSensitivity = p.getFloat("brightnessSensitivity", 1f),
            brightnessReverse = p.getBoolean("brightnessReverse", false),
            themeMode = enumValue(p.getString("themeMode", null), PixelThemeMode.DYNAMIC),
            manualAccentArgb = p.getLong("manualAccentArgb", 0xFF6750A4),
            manualSurfaceArgb = p.getLong("manualSurfaceArgb", 0xFF1B1B1F),
            manualBackgroundArgb = p.getLong("manualBackgroundArgb", 0xFF111114)
        )
    }

    fun save(context: Context, c: PixelShadeConfig) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", c.enabled)
            .putBoolean("editMode", c.editMode)
            .putString("placement", c.placement.name)
            .putFloat("visualHeightDp", c.visualHeightDp)
            .putFloat("detectionHeightDp", c.detectionHeightDp)
            .putFloat("widthPercent", c.widthPercent)
            .putFloat("verticalSwipeThresholdDp", c.verticalSwipeThresholdDp)
            .putFloat("horizontalSwipeThresholdDp", c.horizontalSwipeThresholdDp)
            .putString("swipeDownAction", c.swipeDownAction.name)
            .putBoolean("tapEnabled", c.tapEnabled)
            .putString("tapAction", c.tapAction.name)
            .putBoolean("brightnessSwipeEnabled", c.brightnessSwipeEnabled)
            .putFloat("brightnessSensitivity", c.brightnessSensitivity)
            .putBoolean("brightnessReverse", c.brightnessReverse)
            .putString("themeMode", c.themeMode.name)
            .putLong("manualAccentArgb", c.manualAccentArgb)
            .putLong("manualSurfaceArgb", c.manualSurfaceArgb)
            .putLong("manualBackgroundArgb", c.manualBackgroundArgb)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}
