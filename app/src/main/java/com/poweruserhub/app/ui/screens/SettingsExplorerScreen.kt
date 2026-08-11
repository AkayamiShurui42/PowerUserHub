package com.poweruserhub.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import com.poweruserhub.app.model.SettingItem
import com.poweruserhub.app.model.SettingLock
import com.poweruserhub.app.service.LockDatabaseHelper
import com.poweruserhub.app.service.ShellService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsExplorerScreen(
    shellService: ShellService,
    dbHelper: LockDatabaseHelper
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var selectedNamespace by remember { mutableStateOf("GLOBAL") }
    var searchQuery by remember { mutableStateOf("") }
    
    var settingsList by remember { mutableStateOf<List<SettingItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    
    var activeSettingForSheet by remember { mutableStateOf<SettingItem?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }

    fun refreshSettings() {
        isLoading = true
        coroutineScope.launch {
            settingsList = shellService.readSettingsList(selectedNamespace)
            isLoading = false
        }
    }

    LaunchedEffect(selectedNamespace) {
        refreshSettings()
    }

    val filteredSettings = settingsList.filter {
        it.key.contains(searchQuery, ignoreCase = true) || 
                it.value.contains(searchQuery, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab row for namespaces
        TabRow(
            selectedTabIndex = when (selectedNamespace) {
                "GLOBAL" -> 0
                "SECURE" -> 1
                else -> 2
            }
        ) {
            Tab(
                selected = selectedNamespace == "GLOBAL",
                onClick = { selectedNamespace = "GLOBAL" },
                text = { Text("Global") }
            )
            Tab(
                selected = selectedNamespace == "SECURE",
                onClick = { selectedNamespace = "SECURE" },
                text = { Text("Secure") }
            )
            Tab(
                selected = selectedNamespace == "SYSTEM",
                onClick = { selectedNamespace = "SYSTEM" },
                text = { Text("System") }
            )
        }

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search settings...") },
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
                .padding(16.dp),
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
                items(filteredSettings) { item ->
                    SettingCard(
                        item = item,
                        onClick = {
                            activeSettingForSheet = item
                            showBottomSheet = true
                        }
                    )
                }
                
                if (filteredSettings.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No settings found matching search.",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Bottom Sheet for Edit/Lock Action
    if (showBottomSheet && activeSettingForSheet != null) {
        val setting = activeSettingForSheet!!
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            SettingActionSheetContent(
                setting = setting,
                shellService = shellService,
                dbHelper = dbHelper,
                onDismiss = {
                    showBottomSheet = false
                    refreshSettings()
                }
            )
        }
    }
}

@Composable
fun SettingCard(
    item: SettingItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = item.key,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (item.value.isEmpty()) "[Empty]" else item.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                SuggestionChip(
                    onClick = {},
                    label = { Text(item.namespace, fontSize = 10.sp) },
                    enabled = false
                )
            }
        }
    }
}

@Composable
fun SettingActionSheetContent(
    setting: SettingItem,
    shellService: ShellService,
    dbHelper: LockDatabaseHelper,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var editValue by remember { mutableStateOf(setting.value) }
    var isLockEnabled by remember { mutableStateOf(false) }
    
    // Check if this setting is already locked in db
    LaunchedEffect(setting.key) {
        val existingLock = dbHelper.getAllLocks().find { it.key == setting.key && it.namespace == setting.namespace }
        if (existingLock != null) {
            isLockEnabled = existingLock.isEnabled
            editValue = existingLock.desiredValue
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = setting.key,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SuggestionChip(
                onClick = {},
                label = { Text(setting.namespace) }
            )
            if (isLockEnabled) {
                SuggestionChip(
                    onClick = {},
                    label = { Text("Locked Enforced") },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        labelColor = MaterialTheme.colorScheme.secondary
                    )
                )
            }
        }

        OutlinedTextField(
            value = editValue,
            onValueChange = { editValue = it },
            label = { Text("Setting Value") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Apply Immediately
            Button(
                onClick = {
                    val result = shellService.writeSetting(setting.namespace, setting.key, editValue)
                    if (result.isSuccess) {
                        Toast.makeText(context, "Setting changed successfully.", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    } else {
                        Toast.makeText(context, "Failed: ${result.stderr}", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Apply")
            }

            // Lock / Enforce
            FilledTonalButton(
                onClick = {
                    val timestamp = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date())
                    if (isLockEnabled) {
                        // Disable Lock
                        dbHelper.deleteLock(setting.key)
                        Toast.makeText(context, "Setting unlocked.", Toast.LENGTH_SHORT).show()
                    } else {
                        // Enable Lock
                        val lock = SettingLock(
                            key = setting.key,
                            namespace = setting.namespace,
                            desiredValue = editValue,
                            isEnabled = true,
                            lastVerified = timestamp,
                            status = "Locked"
                        )
                        dbHelper.insertOrUpdateLock(lock)
                        Toast.makeText(context, "Setting locked to '$editValue'.", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isLockEnabled) Icons.Default.LockOpen else Icons.Default.Lock,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isLockEnabled) "Unlock" else "Lock Value")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}
