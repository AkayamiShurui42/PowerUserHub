package com.poweruserhub.app.service

import android.util.TypedValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.ShizukuPlusAPI

/**
 * Shizuku+ proof-of-concept controller for SystemUI fabricated overlays.
 *
 * The upstream Plus bridge registers FabricatedOverlay instances but does not explicitly
 * enable the new OverlayIdentifier. We therefore use the typed Plus bridge to create it,
 * then the compatible Shizuku shell path to discover/enable the generated identifier.
 */
object Pixel17SystemUiController {

    data class Result(val success: Boolean, val message: String)

    private const val TARGET = "com.android.systemui"
    private const val RESOURCE = "com.android.systemui:dimen/qs_tile_corner_radius_default"

    suspend fun applyRadiusProofOfConcept(): Result = withContext(Dispatchers.IO) {
        if (!ShizukuPlusAPI.isEnhancedApiSupported()) {
            return@withContext Result(
                false,
                "Connected Shizuku server does not expose the Shizuku+ enhanced API."
            )
        }

        val before = ShizukuPlusAPI.executeShell(arrayOf("cmd", "overlay", "list", "--user", "current"))
        if (!before.isSuccess()) {
            return@withContext Result(false, "Could not query overlays: ${before.error}")
        }

        // Shizuku+'s injector accepts the integer payload used by FabricatedOverlay's
        // setResourceValue(name, type, int) overload, not a literal string like "2dp".
        val packed2dp = TypedValue.createComplexDimension(2f, TypedValue.COMPLEX_UNIT_DIP)
        val injected = ShizukuPlusAPI.OverlayManager.injectResourceOverlay(
            TARGET,
            RESOURCE,
            TypedValue.TYPE_DIMENSION,
            packed2dp.toString()
        )

        if (!injected) {
            return@withContext Result(
                false,
                "Shizuku+ Overlay Manager Plus rejected the fabricated SystemUI resource."
            )
        }

        val after = ShizukuPlusAPI.executeShell(arrayOf("cmd", "overlay", "list", "--user", "current"))
        val generated = after.output
            .lineSequence()
            .map { it.trim().removePrefix("[x] ").removePrefix("[ ] ").removePrefix("--- ") }
            .firstOrNull { it.contains("shizuku_plus_overlay_") }

        val activationMessage = if (generated != null) {
            val enable = ShizukuPlusAPI.executeShell(
                arrayOf("cmd", "overlay", "enable", "--user", "current", generated)
            )
            if (enable.isSuccess()) {
                "Registered and enabled $generated."
            } else {
                "Registered $generated, but explicit enable returned: ${enable.error.ifBlank { enable.output }}"
            }
        } else {
            "Overlay registration returned success, but its generated identifier was not visible in cmd overlay list."
        }

        val lookup = ShizukuPlusAPI.executeShell(
            arrayOf("cmd", "overlay", "lookup", "--verbose", TARGET, RESOURCE)
        )
        val lookupText = if (lookup.isSuccess()) lookup.output else lookup.error

        Result(
            true,
            "$activationMessage\n\nResolved resource:\n$lookupText"
        )
    }
}
