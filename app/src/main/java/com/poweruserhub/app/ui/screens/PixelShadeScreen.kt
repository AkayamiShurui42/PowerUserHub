package com.poweruserhub.app.ui.screens

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.poweruserhub.app.model.*
import com.poweruserhub.app.service.PixelShadeAccessibilityService
import com.poweruserhub.app.service.PixelShadePreferences
import com.poweruserhub.app.service.PixelShadeTriggerService
import rikka.shizuku.Shizuku

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1718

@Composable
fun PixelShadeScreen() {
    val context = LocalContext.current
    var c by remember { mutableStateOf(PixelShadePreferences.load(context)) }
    var permissionRefresh by remember { mutableIntStateOf(0) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionRefresh++ }

    fun save(next: PixelShadeConfig) {
        c = next
        PixelShadePreferences.save(context, next)
        if (next.enabled && Settings.canDrawOverlays(context)) {
            context.startForegroundService(Intent(context, PixelShadeTriggerService::class.java))
        } else if (!next.enabled) {
            context.stopService(Intent(context, PixelShadeTriggerService::class.java))
        }
    }

    @Suppress("UNUSED_EXPRESSION")
    permissionRefresh

    val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val overlayGranted = Settings.canDrawOverlays(context)
    val shizukuGranted = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    val powerManager = context.getSystemService(PowerManager::class.java)
    val batteryExempt = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

    val enabledListeners = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ).orEmpty()
    val notificationAccessGranted = enabledListeners.contains(context.packageName)

    val accessibilityComponent = ComponentName(context, PixelShadeAccessibilityService::class.java).flattenToString()
    val enabledAccessibility = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    val accessibilityGranted = PixelShadeAccessibilityService.connected ||
        enabledAccessibility.split(':').any { it.equals(accessibilityComponent, ignoreCase = true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("Pixel 17 replacement shade", style = MaterialTheme.typography.headlineSmall)
            Text("Runtime replacement layer. The stock shade can stay installed while this trigger owns the gesture.", style = MaterialTheme.typography.bodyMedium)
        }

        item {
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Permission setup", style = MaterialTheme.typography.titleMedium)
                    Text("The shade checks each capability itself and takes you directly to the correct Android permission flow.", style = MaterialTheme.typography.bodySmall)

                    PermissionRow("Notifications", notificationsGranted) {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    PermissionRow("Notification access", notificationAccessGranted) {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }

                    PermissionRow("Accessibility service", accessibilityGranted) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }

                    PermissionRow("Display over other apps", overlayGranted) {
                        context.startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ))
                    }

                    PermissionRow("Shizuku / Shizuku+", shizukuGranted) {
                        runCatching {
                            if (!Shizuku.pingBinder()) return@runCatching
                            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                            }
                        }
                        permissionRefresh++
                    }

                    PermissionRow("Ignore battery optimization", batteryExempt) {
                        runCatching {
                            context.startActivity(Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            ))
                        }.recoverCatching {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }

                    OutlinedButton(onClick = { permissionRefresh++ }) {
                        Text("Refresh permission status")
                    }
                }
            }
        }

        item {
            ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingSwitch("Enable trigger", c.enabled) { save(c.copy(enabled = it)) }
                SettingSwitch("Edit mode outline", c.editMode) { save(c.copy(editMode = it)) }
                if (!overlayGranted) {
                    Text("Overlay permission is required before the trigger can run.", style = MaterialTheme.typography.bodySmall)
                }
            }}
        }
        item {
            SectionTitle("Trigger placement")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(c.placement == TriggerPlacement.OVER_STATUS_BAR, { save(c.copy(placement = TriggerPlacement.OVER_STATUS_BAR)) }, { Text("Over status bar") })
                FilterChip(c.placement == TriggerPlacement.BELOW_STATUS_BAR, { save(c.copy(placement = TriggerPlacement.BELOW_STATUS_BAR)) }, { Text("Below status bar") })
            }
            FloatSlider("Visible strip height", c.visualHeightDp, 1f..8f, "dp") { save(c.copy(visualHeightDp = it)) }
            FloatSlider("Touch detection height", c.detectionHeightDp, 2f..40f, "dp") { save(c.copy(detectionHeightDp = it)) }
            FloatSlider("Trigger width", c.widthPercent, 20f..100f, "%") { save(c.copy(widthPercent = it)) }
        }
        item {
            SectionTitle("Swipe down")
            FloatSlider("Required swipe length", c.verticalSwipeThresholdDp, 12f..140f, "dp") { save(c.copy(verticalSwipeThresholdDp = it)) }
            ActionChips(c.swipeDownAction) { save(c.copy(swipeDownAction = it)) }
        }
        item {
            SectionTitle("Tap")
            SettingSwitch("Enable tap action", c.tapEnabled) { save(c.copy(tapEnabled = it)) }
            if (c.tapEnabled) ActionChips(c.tapAction) { save(c.copy(tapAction = it)) }
        }
        item {
            SectionTitle("Horizontal brightness gesture")
            SettingSwitch("Swipe left/right for brightness", c.brightnessSwipeEnabled) { save(c.copy(brightnessSwipeEnabled = it)) }
            FloatSlider("Minimum horizontal travel", c.horizontalSwipeThresholdDp, 8f..100f, "dp") { save(c.copy(horizontalSwipeThresholdDp = it)) }
            FloatSlider("Brightness sensitivity", c.brightnessSensitivity, .25f..3f, "×") { save(c.copy(brightnessSensitivity = it)) }
            SettingSwitch("Reverse brightness direction", c.brightnessReverse) { save(c.copy(brightnessReverse = it)) }
            Text("Horizontal and vertical gestures lock to one axis after the threshold, preventing a diagonal swipe from opening the shade while changing brightness.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            SectionTitle("Material adaptive colors")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelThemeMode.entries.forEach { mode ->
                    FilterChip(c.themeMode == mode, { save(c.copy(themeMode = mode)) }, { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }
            Text("Dynamic follows the system wallpaper palette. Hybrid uses the wallpaper palette as the base while allowing per-surface overrides. Manual ignores wallpaper colors.", style = MaterialTheme.typography.bodySmall)
            ColorField("Accent ARGB", c.manualAccentArgb) { save(c.copy(manualAccentArgb = it)) }
            ColorField("Surface ARGB", c.manualSurfaceArgb) { save(c.copy(manualSurfaceArgb = it)) }
            ColorField("Background ARGB", c.manualBackgroundArgb) { save(c.copy(manualBackgroundArgb = it)) }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(if (granted) "Granted" else "Required", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onRequest, enabled = !granted) {
            Text(if (granted) "Ready" else "Grant")
        }
    }
}

@Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium) }

@Composable private fun SettingSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, Modifier.weight(1f)); Switch(value, onChange) }
}

@Composable private fun FloatSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, suffix: String, onChange: (Float) -> Unit) {
    Column { Text("$label: ${"%.1f".format(value)}$suffix"); Slider(value, onChange, valueRange = range) }
}

@Composable private fun ActionChips(selected: TriggerAction, onSelected: (TriggerAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(TriggerAction.OPEN_SHADE, TriggerAction.OPEN_QS).forEach { a -> FilterChip(selected == a, { onSelected(a) }, { Text(a.label()) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(TriggerAction.OPEN_NOTIFICATIONS, TriggerAction.NONE).forEach { a -> FilterChip(selected == a, { onSelected(a) }, { Text(a.label()) }) }
        }
    }
}

private fun TriggerAction.label() = when(this) {
    TriggerAction.OPEN_SHADE -> "Pixel shade"
    TriggerAction.OPEN_QS -> "System QS"
    TriggerAction.OPEN_NOTIFICATIONS -> "System notifications"
    TriggerAction.NONE -> "None"
}

@Composable private fun ColorField(label: String, argb: Long, onChange: (Long) -> Unit) {
    var text by remember(argb) { mutableStateOf(argb.toString(16).uppercase().padStart(8, '0')) }
    OutlinedTextField(value = text, onValueChange = {
        text = it.take(8)
        if (text.length == 8) text.toLongOrNull(16)?.let(onChange)
    }, label = { Text(label) }, supportingText = { Text("8-digit ARGB hex") }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
