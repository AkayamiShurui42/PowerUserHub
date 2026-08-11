package com.poweruserhub.app.ui.screens

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.poweruserhub.app.service.ShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppExplorerScreen(shellService: ShellService) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var appList by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var filterMode by remember { mutableStateOf("ALL") } // "ALL", "USER", "SYSTEM", "ENABLED", "DISABLED"
    var searchQuery by remember { mutableStateOf("") }
    
    var activeAppForSheet by remember { mutableStateOf<AppItem?>(null) }
    var showAppSheet by remember { mutableStateOf(false) }

    fun loadApps() {
        isLoading = true
        coroutineScope.launch(Dispatchers.Default) {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            val list = packages.map { pkg ->
                val isSystem = (pkg.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isEnabled = pkg.applicationInfo.enabled
                val appName = pkg.applicationInfo.loadLabel(pm).toString()
                
                AppItem(
                    packageName = pkg.packageName,
                    appName = appName,
                    isSystem = isSystem,
                    isEnabled = isEnabled,
                    versionName = pkg.versionName ?: "1.0"
                )
            }.sortedBy { it.appName.lowercase(Locale.ROOT) }
            
            withContext(Dispatchers.Main) {
                appList = list
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadApps()
    }

    val filteredApps = remember(appList, filterMode, searchQuery) {
        appList.filter { app ->
            val matchesFilter = when (filterMode) {
                "USER" -> !app.isSystem
                "SYSTEM" -> app.isSystem
                "ENABLED" -> app.isEnabled
                "DISABLED" -> !app.isEnabled
                else -> true
            }
            val matchesSearch = app.appName.contains(searchQuery, ignoreCase = true) || 
                    app.packageName.contains(searchQuery, ignoreCase = true)
            
            matchesFilter && matchesSearch
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter Chips Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val filters = listOf("ALL" to "All", "USER" to "User", "SYSTEM" to "System", "ENABLED" to "Enabled", "DISABLED" to "Disabled")
            filters.forEach { (mode, label) ->
                FilterChip(
                    selected = filterMode == mode,
                    onClick = { filterMode = mode },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search packages...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    AppRowCard(
                        app = app,
                        onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                val detailedApp = fetchAppDetails(context, app, shellService)
                                activeAppForSheet = detailedApp
                                isLoading = false
                                showAppSheet = true
                            }
                        }
                    )
                }
            }
        }
    }

    if (showAppSheet && activeAppForSheet != null) {
        ModalBottomSheet(
            onDismissRequest = { showAppSheet = false },
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            AppDetailSheetContent(
                app = activeAppForSheet!!,
                shellService = shellService,
                onDismiss = {
                    showAppSheet = false
                    loadApps()
                }
            )
        }
    }
}

private suspend fun fetchAppDetails(context: Context, app: AppItem, shellService: ShellService): AppItem = withContext(Dispatchers.Default) {
    val pm = context.packageManager
    return@withContext try {
        val info = pm.getPackageInfo(
            app.packageName,
            PackageManager.GET_ACTIVITIES or 
                    PackageManager.GET_SERVICES or 
                    PackageManager.GET_RECEIVERS or 
                    PackageManager.GET_PROVIDERS or 
                    PackageManager.GET_PERMISSIONS
        )
        
        val permissions = info.requestedPermissions?.toList() ?: emptyList()
        val activities = info.activities?.map { it.name } ?: emptyList()
        val services = info.services?.map { it.name } ?: emptyList()
        val receivers = info.receivers?.map { it.name } ?: emptyList()
        val providers = info.providers?.map { it.name } ?: emptyList()

        val standby = if (shellService.isPrivilegedActive()) shellService.getStandbyBucket(app.packageName) else "unknown"
        val battery = if (shellService.isPrivilegedActive()) shellService.isBatteryExempted(app.packageName) else false
        val restricted = if (shellService.isPrivilegedActive()) shellService.isBackgroundRestricted(app.packageName) else false

        app.copy(
            permissions = permissions,
            activities = activities,
            services = services,
            receivers = receivers,
            providers = providers,
            standbyBucket = standby,
            batteryExempted = battery,
            backgroundRestricted = restricted
        )
    } catch (e: Exception) {
        app
    }
}

@Composable
fun AppRowCard(app: AppItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = app.appName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = app.packageName, 
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            ThemedBadge(text = if (app.isSystem) "System" else "User")
        }
    }
}

@Composable
fun ThemedBadge(text: String) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 10.sp
        )
    }
}

@Composable
fun AppDetailSheetContent(
    app: AppItem,
    shellService: ShellService,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isPrivileged = shellService.isPrivilegedActive()

    var batteryExempted by remember { mutableStateOf(app.batteryExempted) }
    var backgroundRestricted by remember { mutableStateOf(app.backgroundRestricted) }
    var standbyBucket by remember { mutableStateOf(app.standbyBucket) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = app.appName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(text = app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))

        HorizontalDivider()

        // Background policy switches
        Text(text = "App Background Policies", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        
        if (!isPrivileged) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = "Shizuku or Root backend is required to modify or view background policies.",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // Battery optimization
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Exempt from Battery Optimization", fontWeight = FontWeight.Bold)
                Text(text = "Allows app to run fully unrestricted in background.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
            Switch(
                checked = batteryExempted,
                onCheckedChange = { checked ->
                    coroutineScope.launch {
                        val result = shellService.setBatteryExempted(app.packageName, checked)
                        if (result.isSuccess) {
                            batteryExempted = checked
                            Toast.makeText(context, "Optimization policy updated.", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Failed: ${result.stderr}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = isPrivileged
            )
        }

        // Run in background Op
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Restrict background execution", fontWeight = FontWeight.Bold)
                Text(text = "Force stops background service execution rules.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
            Switch(
                checked = backgroundRestricted,
                onCheckedChange = { checked ->
                    coroutineScope.launch {
                        val result = shellService.setBackgroundRestricted(app.packageName, checked)
                        if (result.isSuccess) {
                            backgroundRestricted = checked
                            Toast.makeText(context, "Background permission updated.", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Failed: ${result.stderr}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = isPrivileged
            )
        }

        // Standby bucket
        Text(text = "Standby Optimization Bucket", fontWeight = FontWeight.Bold)
        var expandedBucket by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(
                onClick = { expandedBucket = true },
                enabled = isPrivileged,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Standby Bucket: ${standbyBucket.uppercase(Locale.ROOT)}")
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expandedBucket, onDismissRequest = { expandedBucket = false }) {
                val buckets = listOf("active", "working_set", "frequent", "rare", "restricted")
                buckets.forEach { bucket ->
                    DropdownMenuItem(
                        text = { Text(bucket.uppercase(Locale.ROOT)) },
                        onClick = {
                            expandedBucket = false
                            coroutineScope.launch {
                                val result = shellService.setStandbyBucket(app.packageName, bucket)
                                if (result.isSuccess) {
                                    standbyBucket = bucket
                                    Toast.makeText(context, "Bucket set to $bucket.", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Failed: ${result.stderr}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }
        }

        HorizontalDivider()

        // Component summaries
        Text(text = "App Manifest Components", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        
        ComponentExpandableRow(title = "Activities (${app.activities.size})", list = app.activities)
        ComponentExpandableRow(title = "Services (${app.services.size})", list = app.services)
        ComponentExpandableRow(title = "Broadcast Receivers (${app.receivers.size})", list = app.receivers)
        ComponentExpandableRow(title = "Content Providers (${app.providers.size})", list = app.providers)
        ComponentExpandableRow(title = "Declared Permissions (${app.permissions.size})", list = app.permissions)
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun ComponentExpandableRow(title: String, list: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = title, fontWeight = FontWeight.SemiBold)
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                if (list.isEmpty()) {
                    Text(text = "None declared", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Limit component view to prevent UI thread blocking / frozen frame rates
                        val displayLimit = 30
                        val displayList = if (list.size > displayLimit) list.take(displayLimit) else list
                        
                        displayList.forEach { item ->
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                        }
                        
                        if (list.size > displayLimit) {
                            Text(
                                text = "... and ${list.size - displayLimit} more components",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
