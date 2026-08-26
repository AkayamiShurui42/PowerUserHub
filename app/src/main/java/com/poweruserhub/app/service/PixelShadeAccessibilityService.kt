package com.poweruserhub.app.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Optional accessibility backend for the replacement shade.
 * It gives us a user-authorized fallback for global actions and gesture/window behavior
 * on OEM builds that restrict ordinary application overlays.
 */
class PixelShadeAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        var connected: Boolean = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }
}
