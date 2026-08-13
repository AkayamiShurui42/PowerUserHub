package com.poweruserhub.app.ui.screens

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poweruserhub.app.model.AppItem
import com.poweruserhub.app.service.CommandResult
import com.poweruserhub.app.service.ShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class ComponentKind { SERVICE, RECEIVER }

private data class DeclaredComponent(
    val packageName: String,
    val appName: String,
    val componentName: String,
    val kind: ComponentKind,
    val enabled: Boolean,
    val exported: Boolean,
    val permission: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceMonitorScreen(shellService: ShellService) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(1) }
    var searchQuery by remember { mutableStateOf("") }
    var runningApps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var services by remember { mutableStateOf<List<DeclaredComponent>>(emptyList()) }
    var receivers by remember { mutableStateOf<List<DeclaredComponent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var backendName by remember { mutableStateOf("Detecting…") }

    fun refreshAll() {
        isLoading = true
        coroutineScope.launch {
            val scan = withContext(Dispatchers.IO) {
                shellService.invalidateBackendCache()
                val components = scanDeclaredComponents(context)
                val running = scanRunningProcesses(context, shellService)
                Triple(components.first, components.second, running)
            }
            services = scan.first
            receivers = scan.second
            runningApps = scan.third
            backendName = withContext(Dispatchers.IO) { shellService.getActiveBackendName() }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshAll()
    }

    val filteredServices = remember(services, searchQuery) {
        services.filter { it.matches(searchQuery) }
    }
    val filteredReceivers = remember(receivers, searchQuery) {
        receivers.filter { it.matches(searchQuery) }
    }
    val filteredRunning = remember(runningApps, searchQuery) {
        runningApps.filter {
            it.appName.contains(searchQuery, true) || it.packageName.contains(searchQuery, true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Component Explorer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "$backendName · ${services.size} services · ${receivers.size} receivers",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            IconButton(onClick = { refreshAll() }, enabled = !isLoading) {
                Icon(Icons.Default.Refresh, contentDescription = "Rescan components")
            }
        }

        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Running (${runningApps.size})") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Services (${services.size})") })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Broadcasts (${receivers.size})") })
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            placeholder = { Text("Search app, package, or component…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        if (selectedTab != 0) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Text(
                    text = if (selectedTab == 1)
                        "Declared Android services are enumerated directly from every visible package manifest. Start/stop and component enable/disable actions are executed through the active privileged backend."
                    else
                        "These are declared broadcast receiver endpoints. Send performs an explicit broadcast; enter an action when the receiver expects one. Protected/non-exported receivers can still reject shell requests depending on the active UID.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        when (selectedTab) {
            0 -> RunningProcessList(filteredRunning)
            1 -> ComponentList(filteredServices, shellService)
            else -> ComponentList(filteredReceivers, shellService)
        }
    }
}

private fun DeclaredComponent.matches(query: String): Boolean {
    if (query.isBlank()) return true
    return appName.contains(query, true) ||
        packageName.contains(query, true) ||
        componentName.contains(query, true) ||
        (permission?.contains(query, true) == true)
}

@Suppress("DEPRECATION")
private fun scanDeclaredComponents(context: Context): Pair<List<DeclaredComponent>, List<DeclaredComponent>> {
    val pm = context.packageManager
    val flags = PackageManager.GET_SERVICES or
        PackageManager.GET_RECEIVERS or
        PackageManager.MATCH_DISABLED_COMPONENTS

    val serviceRows = mutableListOf<DeclaredComponent>()
    val receiverRows = mutableListOf<DeclaredComponent>()

    pm.getInstalledPackages(flags).forEach { pkg ->
        val appInfo = pkg.applicationInfo
        val appName = try {
            appInfo?.loadLabel(pm)?.toString() ?: pkg.packageName
        } catch (_: Exception) {
            pkg.packageName
        }
        val appEnabled = appInfo?.enabled ?: true

        pkg.services?.forEach { info ->
            serviceRows += DeclaredComponent(
                packageName = pkg.packageName,
                appName = appName,
                componentName = info.name,
                kind = ComponentKind.SERVICE,
                enabled = appEnabled && info.enabled,
                exported = info.exported,
                permission = info.permission
            )
        }

        pkg.receivers?.forEach { info ->
            receiverRows += DeclaredComponent(
                packageName = pkg.packageName,
                appName = appName,
                componentName = info.name,
                kind = ComponentKind.RECEIVER,
                enabled = appEnabled && info.enabled,
                exported = info.exported,
                permission = info.permission
            )
        }
    }

    val comparator = compareBy<DeclaredComponent>(
        { it.appName.lowercase(Locale.ROOT) },
        { it.componentName.lowercase(Locale.ROOT) }
    )
    return serviceRows.distinctBy { it.packageName + "/" + it.componentName }.sortedWith(comparator) to
        receiverRows.distinctBy { it.packageName + "/" + it.componentName }.sortedWith(comparator)
}

private fun scanRunningProcesses(context: Context, shellService: ShellService): List<AppItem> {
    if (!shellService.isPrivilegedActive()) return emptyList()
    val result = shellService.executeCommand("ps -A")
    if (!result.isSuccess) return emptyList()

    val runningPackages = mutableSetOf<String>()
    result.stdout.lineSequence().forEach { line ->
        val parts = line.trim().split(Regex("\\s+"))
        val name = parts.lastOrNull() ?: return@forEach
        if (name.contains('.') && !name.contains('/') && !name.startsWith("[")) {
            runningPackages += name.substringBefore(':')
        }
    }

    val pm = context.packageManager
    return runningPackages.mapNotNull { pkg ->
        try {
            @Suppress("DEPRECATION")
            val appInfo = pm.getApplicationInfo(pkg, 0)
            AppItem(
                packageName = pkg,
                appName = appInfo.loadLabel(pm).toString(),
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                isEnabled = appInfo.enabled,
                versionName = ""
            )
        } catch (_: Exception) {
            null
        }
    }.sortedBy { it.appName.lowercase(Locale.ROOT) }
}

@Composable
private fun RunningProcessList(apps: List<AppItem>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(apps, key = { it.packageName }) { app -> RunningAppCard(app) }
        if (apps.isEmpty()) {
            item { EmptyComponentMessage("No running application processes were mapped to visible packages.") }
        }
    }
}

@Composable
private fun ComponentList(components: List<DeclaredComponent>, shellService: ShellService) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = components,
            key = { "${it.kind}:${it.packageName}/${it.componentName}" }
        ) { component ->
            ComponentControlCard(component, shellService)
        }
        if (components.isEmpty()) {
            item { EmptyComponentMessage("No matching declared components were found.") }
        }
    }
}

@Composable
private fun ComponentControlCard(component: DeclaredComponent, shellService: ShellService) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var action by remember(component.packageName, component.componentName) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun runOperation(label: String, block: () -> CommandResult) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            busy = false
            val message = if (result.isSuccess) {
                "$label succeeded${result.stdout.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}"
            } else {
                "$label failed (${result.exitCode}): ${result.stderr.ifBlank { result.stdout.ifBlank { "No output" } }}"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (component.kind == ComponentKind.SERVICE) Icons.Default.SettingsApplications else Icons.Default.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(component.appName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text(component.componentName, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                    Text(component.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ComponentBadge(if (component.enabled) "Enabled" else "Disabled")
                ComponentBadge(if (component.exported) "Exported" else "Private")
                component.permission?.takeIf { it.isNotBlank() }?.let { ComponentBadge("Permission") }
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                component.permission?.takeIf { it.isNotBlank() }?.let {
                    Text("Required permission: $it", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                }

                if (component.kind == ComponentKind.RECEIVER) {
                    OutlinedTextField(
                        value = action,
                        onValueChange = { action = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Broadcast action (optional)") },
                        placeholder = { Text("android.intent.action.…") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (component.kind == ComponentKind.SERVICE) {
                        Button(
                            onClick = {
                                runOperation("Start service") {
                                    shellService.startService(component.packageName, component.componentName)
                                }
                            },
                            enabled = !busy && shellService.isPrivilegedActive(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Start") }
                        OutlinedButton(
                            onClick = {
                                runOperation("Stop service") {
                                    shellService.stopService(component.packageName, component.componentName)
                                }
                            },
                            enabled = !busy && shellService.isPrivilegedActive(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Stop") }
                    } else {
                        Button(
                            onClick = {
                                runOperation("Send broadcast") {
                                    shellService.sendBroadcast(
                                        component.packageName,
                                        component.componentName,
                                        action.takeIf { it.isNotBlank() }
                                    )
                                }
                            },
                            enabled = !busy && shellService.isPrivilegedActive(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Send") }
                    }
                }

                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = {
                        runOperation(if (component.enabled) "Disable component" else "Enable component") {
                            shellService.setComponentEnabled(
                                component.packageName,
                                component.componentName,
                                !component.enabled
                            )
                        }
                    },
                    enabled = !busy && shellService.isPrivilegedActive(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(if (component.enabled) Icons.Default.Block else Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (component.enabled) "Disable component" else "Enable component")
                }
            }
        }
    }
}

@Composable
private fun ComponentBadge(text: String) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
    }
}

@Composable
private fun EmptyComponentMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
    }
}

@Composable
fun RunningAppCard(app: AppItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.appName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            ComponentBadge(if (app.isSystem) "System" else "User")
        }
    }
}
