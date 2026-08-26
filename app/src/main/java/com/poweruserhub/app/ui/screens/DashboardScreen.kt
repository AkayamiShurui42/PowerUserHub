package com.poweruserhub.app.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.poweruserhub.app.service.Pixel17SystemUiController
import com.poweruserhub.app.service.ShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    shellService: ShellService,
    onNavigateToSettings: () -> Unit,
    onNavigateToApps: () -> Unit,
    onNavigateToLocks: () -> Unit
) {
    var shizukuActive by remember { mutableStateOf(false) }
    var shizukuPresent by remember { mutableStateOf(false) }
    var rootActive by remember { mutableStateOf(false) }
    var activeBackend by remember { mutableStateOf("Detecting…") }
    var probeText by remember { mutableStateOf("") }
    var plusDetected by remember { mutableStateOf(false) }
    var pixelTestRunning by remember { mutableStateOf(false) }
    var pixelTestResult by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            val state = withContext(Dispatchers.IO) {
                shellService.invalidateBackendCache()
                val binder = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
                val authorized = try {
                    binder && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
                } catch (_: Throwable) {
                    false
                }
                val plus = shellService.isShizukuPlusInstalled()
                val backend = shellService.getActiveBackendName()
                val root = shellService.getActiveExecutor()?.getName() == "Root (su)"
                val probe = if (authorized) shellService.getPrivilegeProbe() else null
                arrayOf(
                    binder.toString(),
                    authorized.toString(),
                    plus.toString(),
                    backend,
                    root.toString(),
                    probe?.stdout.orEmpty()
                )
            }

            shizukuPresent = state[0].toBoolean()
            shizukuActive = state[1].toBoolean()
            plusDetected = state[2].toBoolean()
            activeBackend = state[3]
            rootActive = state[4].toBoolean()
            probeText = state[5]
            delay(2000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Box(
                modifier = Modifier
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.05f)
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        "Power User Hub",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Privileged Android settings, package, component, and process control.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Text("Privilege Provider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(
                title = if (plusDetected) "Shizuku+" else "Shizuku",
                status = when {
                    shizukuActive -> "Authorized"
                    shizukuPresent -> "Permission needed"
                    else -> "Not connected"
                },
                isActive = shizukuActive,
                icon = Icons.Default.Security,
                modifier = Modifier.weight(1f),
                onClick = {
                    try {
                        if (Shizuku.pingBinder() &&
                            Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            Shizuku.requestPermission(100)
                        }
                    } catch (_: Throwable) {
                    }
                }
            )
            StatusCard(
                title = "Root",
                status = if (rootActive) "Available" else "Not selected",
                isActive = rootActive,
                icon = Icons.Default.Android,
                modifier = Modifier.weight(1f)
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Active execution identity", style = MaterialTheme.typography.labelSmall)
                        Text(activeBackend, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                }
                if (probeText.isNotBlank()) {
                    HorizontalDivider()
                    Text(
                        probeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Text(
                    "The actual server UID determines what Android will allow. Shizuku+ can provide additional bridges, but Power User Hub verifies the identity and command availability instead of assuming privileges from the app name.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }

        Text("Pixel 17 SystemUI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Proof-of-concept: inject a 2dp Quick Settings tile radius through Shizuku+ Overlay Manager Plus. If it works, the OxygenOS QS tiles should become visibly more square.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    enabled = shizukuActive && plusDetected && !pixelTestRunning,
                    onClick = {
                        pixelTestRunning = true
                        pixelTestResult = "Running SystemUI overlay test…"
                        scope.launch {
                            val result = Pixel17SystemUiController.applyRadiusProofOfConcept()
                            pixelTestResult = result.message
                            pixelTestRunning = false
                        }
                    }
                ) {
                    if (pixelTestRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Test Pixel 17 overlay")
                }
                if (pixelTestResult.isNotBlank()) {
                    Text(
                        pixelTestResult,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Text("Device Information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoRow("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
                InfoRow("Android", "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
                InfoRow("Build", Build.DISPLAY)
                InfoRow("Hardware", Build.HARDWARE)
            }
        }

        Text("Quick Tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NavigationButton(
                "Settings Explorer",
                "Enumerate and edit Global, Secure, and System tables",
                Icons.Default.Settings,
                onNavigateToSettings
            )
            NavigationButton(
                "App Explorer",
                "Inspect installed packages and manifest components",
                Icons.Default.Apps,
                onNavigateToApps
            )
            NavigationButton(
                "Locked Settings",
                "View settings that are being enforced",
                Icons.Default.Lock,
                onNavigateToLocks
            )
        }
    }
}

@Composable
fun RowScope.StatusCard(
    title: String,
    status: String,
    isActive: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                status,
                color = if (isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}
