package com.poweruserhub.app.service

import android.os.Build
import android.util.TypedValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.ShizukuPlusAPI

/**
 * Proof-of-concept controller for Pixel 17-style SystemUI resource overrides.
 *
 * OxygenOS SystemUI exposes no named overlayable group on the tested device,
 * so an ordinary differently-signed RRO APK is rejected by PackageManager.
 * This controller instead asks Shizuku+ Overlay Manager Plus to register a
 * fabricated overlay directly with OverlayManagerService.
 */
object Pixel17SystemUiController {

    private const val TARGET = "com.android.systemui"
    private const val TEST_RESOURCE =
        "com.android.systemui:dimen/qs_tile_corner_radius_default"

    data class TestResult(
        val success: Boolean,
        val message: String,
    )

    suspend fun applyRadiusProofOfConcept(): TestResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 34) {
            return@withContext TestResult(false, "Android 14+ is required for this test")
        }

        if (!ShizukuPlusAPI.isEnhancedApiSupported()) {
            return@withContext TestResult(false, "Shizuku+ enhanced API is not available")
        }

        val before = overlayIdentifiers()

        // Shizuku+'s current server API expects non-string resource data as a
        // decimal integer. Encode 2dp exactly as Android's TYPE_DIMENSION data.
        val encoded2Dp = TypedValue.createComplexDimension(
            2f,
            TypedValue.COMPLEX_UNIT_DIP,
        )

        val injected = try {
            ShizukuPlusAPI.OverlayManager.injectResourceOverlay(
                TARGET,
                TEST_RESOURCE,
                TypedValue.TYPE_DIMENSION,
                encoded2Dp.toString(),
            )
        } catch (t: Throwable) {
            return@withContext TestResult(false, "Injection threw ${t.javaClass.simpleName}: ${t.message}")
        }

        if (!injected) {
            return@withContext TestResult(false, "Shizuku+ rejected the fabricated overlay request")
        }

        val after = overlayIdentifiers()
        val newlyRegistered = after.filter { it !in before && it.contains("shizuku_plus_overlay") }

        // Upstream Shizuku+ currently registers the FabricatedOverlay but does
        // not explicitly enable it in the same transaction. Discover the
        // generated overlay identifier and activate it through the shell bridge.
        val candidate = newlyRegistered.lastOrNull()
            ?: after.lastOrNull { it.contains("shizuku_plus_overlay") }

        if (candidate == null) {
            return@withContext TestResult(
                false,
                "Overlay registration returned success, but no fabricated overlay identifier was visible",
            )
        }

        val enable = ShizukuPlusAPI.executeShell(
            arrayOf("cmd", "overlay", "enable", "--user", "0", candidate),
        )

        if (!enable.isSuccess) {
            return@withContext TestResult(
                false,
                "Registered $candidate but enable failed: ${enable.error.ifBlank { enable.output }}",
            )
        }

        val lookup = ShizukuPlusAPI.executeShell(
            arrayOf("cmd", "overlay", "lookup", "--user", "0", TARGET, TEST_RESOURCE),
        )

        TestResult(
            lookup.isSuccess,
            if (lookup.isSuccess) {
                "Pixel test active: $candidate; resolved radius=${lookup.output}"
            } else {
                "Overlay enabled as $candidate, but lookup failed: ${lookup.error.ifBlank { lookup.output }}"
            },
        )
    }

    private fun overlayIdentifiers(): List<String> {
        val result = ShizukuPlusAPI.executeShell(
            arrayOf("cmd", "overlay", "list", "--user", "0"),
        )
        if (!result.isSuccess) return emptyList()

        return result.output
            .lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                when {
                    line.startsWith("[x] ") -> line.removePrefix("[x] ").trim()
                    line.startsWith("[ ] ") -> line.removePrefix("[ ] ").trim()
                    line.startsWith("--- ") -> line.removePrefix("--- ").trim()
                    else -> null
                }
            }
            .filter { it.isNotBlank() }
            .toList()
    }
}
