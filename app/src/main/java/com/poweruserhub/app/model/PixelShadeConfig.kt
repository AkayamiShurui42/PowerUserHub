package com.poweruserhub.app.model

enum class TriggerPlacement { OVER_STATUS_BAR, BELOW_STATUS_BAR }
enum class TriggerAction { OPEN_SHADE, OPEN_QS, OPEN_NOTIFICATIONS, NONE }
enum class PixelThemeMode { DYNAMIC, MANUAL, HYBRID }

data class PixelShadeConfig(
    val enabled: Boolean = false,
    val editMode: Boolean = false,
    val placement: TriggerPlacement = TriggerPlacement.OVER_STATUS_BAR,
    val visualHeightDp: Float = 2f,
    val detectionHeightDp: Float = 12f,
    val widthPercent: Float = 100f,
    val verticalSwipeThresholdDp: Float = 40f,
    val horizontalSwipeThresholdDp: Float = 24f,
    val swipeDownAction: TriggerAction = TriggerAction.OPEN_SHADE,
    val tapEnabled: Boolean = false,
    val tapAction: TriggerAction = TriggerAction.OPEN_SHADE,
    val brightnessSwipeEnabled: Boolean = true,
    val brightnessSensitivity: Float = 1f,
    val brightnessReverse: Boolean = false,
    val themeMode: PixelThemeMode = PixelThemeMode.DYNAMIC,
    val manualAccentArgb: Long = 0xFF6750A4,
    val manualSurfaceArgb: Long = 0xFF1B1B1F,
    val manualBackgroundArgb: Long = 0xFF111114
)
