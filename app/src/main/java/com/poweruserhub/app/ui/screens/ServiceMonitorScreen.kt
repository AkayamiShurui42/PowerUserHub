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
import com.poweruserhub.app.service.ServiceKnowledge
import com.poweruserhub.app.service.ServiceKnowledgeStore
import com.poweruserhub.app.service.ServiceProtectionController
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
    val knowledgeStore = remember { ServiceKnowledgeStore(context) }
    val protectionController = remember { ServiceProtectionController(context) }

    var selectedTab by remember { mutableIntStateOf(1) }
    var searchQuery by remember { mutableStateOf("") }
    var runningApps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var services by remember { mutableStateOf<List<DeclaredComponent>>(emptyList()) }
    var receivers by remember { mutableStateOf<List<DeclaredComponent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var backendName by remember { mutableStateOf("Detecting…") }
    var protectionGeneration by remember { mutableIntStateOf(0) }
    var protectionDiagnostic by remember { mutableStateOf("") }

    fun refreshAll(rehydrateProtection: Boolean = false) {
        isLoading = true
        coroutineScope.launch {
            val scan = withContext(Dispatchers.IO) {
                shellService.invalidateBackendCache()
                val components = scanDeclaredComponents(context)
                val running = scanRunningProcesses(context, shellService)

                if (protectionController.isAvailable()) {
                    if (rehydrateProtection) {
                        val wanted = knowledgeStore.getProtectedSpecs()
                        if (wanted.isNotEmpty()) {
                            protectionController.restoreProtection(wanted)
                        }
                    }
                    knowledgeStore.applyWatchdogEvents(protectionController.drainEvents())
                }

                Triple(components.first, components.second, running)
            }
            services = scan.first
            receivers = scan.second
            runningApps = scan.third
            backendName = withContext(Dispatchers.IO) { shellService.getActiveBackendName() }
            protectionDiagnostic = when {
                protectionController.isAvailable() -> "Shizuku+ daemon protection ready"
                protectionController.isShizukuPlusInstalled() -> "Shizuku+ installed but not authorized/connected"
                else -> "Shizuku+ required for Keep Alive"
            }
            protectionGeneration++
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshAll(rehydrateProtection = true)
    }

    val filteredServices = remember(services, searchQuery, protectionGeneration) {
        services.filter { component ->
            if (component.matches(searchQuery)) return@filter true
            if (searchQuery.isBlank()) return@filter true
            val knowledge = knowledgeStore.get(component.packageName, component.componentName)
            knowledge.displayName.contains(searchQuery, true) ||
                knowledge.description.contains(searchQuery, true) ||
                knowledge.confidence.label.contains(searchQuery, true)
        }
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
                Text(
                    protectionDiagnostic,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (protectionController.isAvailable())
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
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
            placeholder = { Text("Search name, purpose, app, package, or component…") },
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
                        "Services are shown with human-readable terminology while the raw Android component remains visible. PowerHub learns from starts, stops, disappearances, and watchdog restores. Keep Alive uses the persistent Shizuku+ UserService and re-applies background protections when a protected service disappears."
                    else
                        "These are declared broadcast receiver endpoints. Send performs an explicit broadcast; enter an action when the receiver expects one. Protected/non-exported receivers can still reject privileged requests depending on the active UID.",
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
            1 -> ComponentList(
                components = filteredServices,
                shellService = shellService,
                knowledgeStore = knowledgeStore,
                protectionController = protectionController,
                protectionGeneration = protectionGeneration,
                onProtectionChanged = {
                    protectionGeneration++
                }
            )
            else -> ComponentList(
                components = filteredReceivers,
                shellService = shellService,
                knowledgeStore = knowledgeStore,
                protectionController = protectionController,
                protectionGeneration = protectionGeneration,
                onProtectionChanged = {}
            )
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
private fun ComponentList(
    components: List<DeclaredComponent>,
    shellService: ShellService,
    knowledgeStore: ServiceKnowledgeStore,
    protectionController: ServiceProtectionController,
    protectionGeneration: Int,
    onProtectionChanged: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = components,
            key = { "${it.kind}:${it.packageName}/${it.componentName}" }
        ) { component ->
            val knowledge = if (component.kind == ComponentKind.SERVICE) {
                // Read again when protectionGeneration changes so event history is reflected.
                @Suppress("UNUSED_VARIABLE")
                val generation = protectionGeneration
                knowledgeStore.get(component.packageName, component.componentName)
            } else null
            ComponentControlCard(
                component = component,
                shellService = shellService,
                knowledgeStore = knowledgeStore,
                protectionController = protectionController,
                knowledge = knowledge,
                onProtectionChanged = onProtectionChanged
            )
        }
        if (components.isEmpty()) {
            item { EmptyComponentMessage("No matching declared components were found.") }
        }
    }
}

@Composable
private fun ComponentControlCard(
    component: DeclaredComponent,
    shellService: ShellService,
    knowledgeStore: ServiceKnowledgeStore,
    protectionController: ServiceProtectionController,
    knowledge: ServiceKnowledge?,
    onProtectionChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var action by remember(component.packageName, component.componentName) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var protected by remember(component.packageName, component.componentName, knowledge?.protected) {
        mutableStateOf(knowledge?.protected == true)
    }
    var showTeachDialog by remember { mutableStateOf(false) }

    fun runOperation(
        label: String,
        block: () -> CommandResult,
        onSuccess: (() -> Unit)? = null
    ) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            busy = false
            val message = if (result.isSuccess) {
                onSuccess?.invoke()
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
                    if (knowledge != null) {
                        Text(knowledge.displayName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(component.appName, style = MaterialTheme.typography.labelSmall)
                        Text(
                            knowledge.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                            maxLines = if (expanded) 6 else 2
                        )
                    } else {
                        Text(component.appName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        component.componentName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 2
                    )
                    Text(
                        component.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ComponentBadge(if (component.enabled) "Enabled" else "Disabled")
                ComponentBadge(if (component.exported) "Exported" else "Private")
                component.permission?.takeIf { it.isNotBlank() }?.let { ComponentBadge("Permission") }
                knowledge?.let { ComponentBadge(it.confidence.label) }
                if (protected) ComponentBadge("Keep Alive")
            }

            if (knowledge != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    knowledge.observationSummary(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                )
                knowledge.lastEvent.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "Last learned event: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                    )
                }
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
                                runOperation(
                                    label = "Start service",
                                    block = { shellService.startService(component.packageName, component.componentName) },
                                    onSuccess = {
                                        knowledgeStore.recordManualStart(component.packageName, component.componentName)
                                        onProtectionChanged()
                                    }
                                )
                            },
                            enabled = !busy && shellService.isPrivilegedActive(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Start") }
                        OutlinedButton(
                            onClick = {
                                runOperation(
                                    label = "Stop service",
                                    block = { shellService.stopService(component.packageName, component.componentName) },
                                    onSuccess = {
                                        knowledgeStore.recordManualStop(component.packageName, component.componentName)
                                        onProtectionChanged()
                                    }
                                )
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

                if (component.kind == ComponentKind.SERVICE) {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            val newState = !protected
                            runOperation(
                                label = if (newState) "Enable Keep Alive" else "Disable Keep Alive",
                                block = {
                                    protectionController.protect(
                                        component.packageName,
                                        component.componentName,
                                        newState
                                    )
                                },
                                onSuccess = {
                                    protected = newState
                                    knowledgeStore.setProtected(component.packageName, component.componentName, newState)
                                    onProtectionChanged()
                                }
                            )
                        },
                        enabled = !busy && protectionController.isAvailable(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(if (protected) Icons.Default.Shield else Icons.Default.ShieldMoon, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (protected) "Disable Keep Alive" else "Keep service alive with Shizuku+")
                    }

                    if (!protectionController.isAvailable()) {
                        Text(
                            text = if (protectionController.isShizukuPlusInstalled())
                                "Keep Alive is unavailable until Shizuku+ is connected and Power User Hub is authorized."
                            else
                                "Keep Alive intentionally requires Shizuku+ so the watchdog can live in a daemon privileged UserService.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showTeachDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.EditNote, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Teach PowerHub what this service does")
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

    if (showTeachDialog && component.kind == ComponentKind.SERVICE) {
        val existing = knowledgeStore.get(component.packageName, component.componentName)
        var label by remember(component.packageName, component.componentName, showTeachDialog) {
            mutableStateOf(existing.displayName)
        }
        var description by remember(component.packageName, component.componentName, showTeachDialog) {
            mutableStateOf(existing.description)
        }
        AlertDialog(
            onDismissRequest = { showTeachDialog = false },
            title = { Text("Teach service terminology") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "PowerHub keeps the raw component name, but this local terminology can later be submitted to the community knowledge database with device-specific observations.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Human-readable name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("What this service does") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        knowledgeStore.setCustomMetadata(
                            component.packageName,
                            component.componentName,
                            label,
                            description
                        )
                        showTeachDialog = false
                        onProtectionChanged()
                    }
                ) { Text("Save learning") }
            },
            dismissButton = {
                TextButton(onClick = { showTeachDialog = false }) { Text("Cancel") }
            }
        )
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
