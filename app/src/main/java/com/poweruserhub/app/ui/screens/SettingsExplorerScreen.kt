package com.poweruserhub.app.ui.screens

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
import com.poweruserhub.app.model.SettingItem
import com.poweruserhub.app.model.SettingLock
import com.poweruserhub.app.service.LockDatabaseHelper
import com.poweruserhub.app.service.SettingKnowledge
import com.poweruserhub.app.service.SettingKnowledgeStore
import com.poweruserhub.app.service.SettingObservationEngine
import com.poweruserhub.app.service.ShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val knowledgeStore = remember { SettingKnowledgeStore(context) }

    var selectedNamespace by remember { mutableStateOf("GLOBAL") }
    var searchQuery by remember { mutableStateOf("") }
    var settingsList by remember { mutableStateOf<List<SettingItem>>(emptyList()) }
    var diagnostic by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var activeSettingForSheet by remember { mutableStateOf<SettingItem?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var knowledgeGeneration by remember { mutableIntStateOf(0) }

    fun refreshSettings() {
        isLoading = true
        coroutineScope.launch {
            val rows = withContext(Dispatchers.IO) {
                shellService.invalidateBackendCache()
                shellService.readSettingsList(selectedNamespace)
            }
            settingsList = rows
            diagnostic = shellService.getLastSettingsDiagnostic()
            isLoading = false
        }
    }

    LaunchedEffect(selectedNamespace) {
        refreshSettings()
    }

    val filteredSettings = remember(settingsList, searchQuery, knowledgeGeneration) {
        settingsList.filter { item ->
            if (item.key.contains(searchQuery, ignoreCase = true) ||
                item.value.contains(searchQuery, ignoreCase = true)
            ) return@filter true
            if (searchQuery.isBlank()) return@filter true
            val knowledge = knowledgeStore.get(item.namespace, item.key)
            knowledge.displayName.contains(searchQuery, true) ||
                knowledge.description.contains(searchQuery, true) ||
                knowledge.acceptedValues.any { it.contains(searchQuery, true) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = when (selectedNamespace) {
                "GLOBAL" -> 0
                "SECURE" -> 1
                else -> 2
            }
        ) {
            listOf("GLOBAL" to "Global", "SECURE" to "Secure", "SYSTEM" to "System").forEach { (ns, label) ->
                Tab(
                    selected = selectedNamespace == ns,
                    onClick = { selectedNamespace = ns },
                    text = { Text(label) }
                )
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search name, purpose, key, or value…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                Row {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                    IconButton(onClick = { refreshSettings() }, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload settings")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        if (diagnostic.isNotBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (settingsList.isEmpty())
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (settingsList.isEmpty()) Icons.Default.Warning else Icons.Default.Verified,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = shellService.getActiveBackendName(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = diagnostic,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

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
                items(filteredSettings, key = { "${it.namespace}_${it.key}" }) { item ->
                    val knowledge = knowledgeStore.get(item.namespace, item.key)
                    SettingCard(
                        item = item,
                        knowledge = knowledge,
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
                                text = if (settingsList.isEmpty())
                                    "No rows were returned from the $selectedNamespace settings table. See the backend diagnostic above."
                                else
                                    "No settings match this search.",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }

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
                knowledgeStore = knowledgeStore,
                onKnowledgeChanged = { knowledgeGeneration++ },
                onDismiss = {
                    showBottomSheet = false
                    refreshSettings()
                }
            )
        }
    }
}

@Composable
fun SettingCard(item: SettingItem, knowledge: SettingKnowledge, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = knowledge.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = knowledge.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.key,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
                maxLines = 1
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
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                SettingsBadge(text = knowledge.confidenceLabel)
                Spacer(modifier = Modifier.width(6.dp))
                SettingsBadge(text = item.namespace)
            }
        }
    }
}

@Composable
private fun SettingsBadge(text: String) {
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
fun SettingActionSheetContent(
    setting: SettingItem,
    shellService: ShellService,
    dbHelper: LockDatabaseHelper,
    knowledgeStore: SettingKnowledgeStore,
    onKnowledgeChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var editValue by remember(setting.key, setting.namespace) { mutableStateOf(setting.value) }
    var isLockEnabled by remember { mutableStateOf(false) }
    var isApplying by remember { mutableStateOf(false) }
    var knowledge by remember(setting.key, setting.namespace) {
        mutableStateOf(knowledgeStore.get(setting.namespace, setting.key))
    }
    var showTeachDialog by remember { mutableStateOf(false) }

    fun reloadKnowledge() {
        knowledge = knowledgeStore.get(setting.namespace, setting.key)
        onKnowledgeChanged()
    }

    LaunchedEffect(setting.key, setting.namespace) {
        val existingLock = withContext(Dispatchers.IO) {
            dbHelper.getAllLocks().find { it.key == setting.key && it.namespace == setting.namespace }
        }
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(knowledge.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            knowledge.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
        )
        Text(
            setting.key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuggestionChip(onClick = {}, label = { Text(setting.namespace) })
            SuggestionChip(onClick = {}, label = { Text(knowledge.confidenceLabel) })
            if (isLockEnabled) {
                SuggestionChip(onClick = {}, label = { Text("Locked / enforced") })
            }
        }

        if (knowledge.acceptedValues.isNotEmpty()) {
            Text("Values accepted on this device", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(
                knowledge.acceptedValues.take(12).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (knowledge.rejectedValues.isNotEmpty()) {
            Text("Rejected values", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(
                knowledge.rejectedValues.take(12).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        knowledge.correlations.take(3).forEach { correlation ->
            Text(
                "Observed alongside: ${correlation.namespace}.${correlation.key} ${correlation.before} → ${correlation.after} (${correlation.observations}×)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
            )
        }
        knowledge.lastResult.takeIf { it.isNotBlank() }?.let {
            Text("Last experiment: $it", style = MaterialTheme.typography.labelSmall)
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
            Button(
                onClick = {
                    isApplying = true
                    coroutineScope.launch {
                        val experiment = withContext(Dispatchers.IO) {
                            val observer = SettingObservationEngine(shellService)
                            val oldValue = shellService.readSetting(setting.namespace, setting.key)
                            val before = observer.snapshot()
                            val result = shellService.writeSetting(setting.namespace, setting.key, editValue)
                            if (!result.isSuccess) {
                                knowledgeStore.recordRejected(
                                    setting.namespace,
                                    setting.key,
                                    editValue,
                                    result.stderr.ifBlank { result.stdout.ifBlank { "write failed" } }
                                )
                                return@withContext Triple(result, oldValue, emptyList())
                            }
                            // Give SettingsProvider listeners/OEM services a short chance to react.
                            Thread.sleep(400L)
                            val readBack = shellService.readSetting(setting.namespace, setting.key)
                            val after = observer.snapshot()
                            val correlations = observer.diff(
                                before,
                                after,
                                setting.namespace,
                                setting.key
                            )
                            knowledgeStore.recordAccepted(
                                namespace = setting.namespace,
                                key = setting.key,
                                oldValue = oldValue,
                                requestedValue = editValue,
                                readBackValue = readBack,
                                correlations = correlations
                            )
                            Triple(result, readBack, correlations)
                        }
                        isApplying = false
                        reloadKnowledge()
                        val result = experiment.first
                        if (result.isSuccess) {
                            val effects = experiment.third.size
                            Toast.makeText(
                                context,
                                "Setting written and verified. Learned ${if (effects == 0) "no immediate correlated setting changes" else "$effects correlated change${if (effects == 1) "" else "s"}"}.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(context, "Failed: ${result.stderr}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !isApplying,
                modifier = Modifier.weight(1f)
            ) {
                if (isApplying) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Apply + Learn")
                }
            }

            FilledTonalButton(
                onClick = {
                    val timestamp = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date())
                    if (isLockEnabled) {
                        dbHelper.deleteLock(setting.key)
                        Toast.makeText(context, "Setting unlocked.", Toast.LENGTH_SHORT).show()
                    } else {
                        dbHelper.insertOrUpdateLock(
                            SettingLock(
                                key = setting.key,
                                namespace = setting.namespace,
                                desiredValue = editValue,
                                isEnabled = true,
                                lastVerified = timestamp,
                                status = "Locked"
                            )
                        )
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

        OutlinedButton(
            onClick = { showTeachDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.EditNote, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Teach PowerHub what this setting does")
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showTeachDialog) {
        var label by remember(showTeachDialog) { mutableStateOf(knowledge.displayName) }
        var description by remember(showTeachDialog) { mutableStateOf(knowledge.description) }
        AlertDialog(
            onDismissRequest = { showTeachDialog = false },
            title = { Text("Teach setting terminology") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        label = { Text("What it does") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        knowledgeStore.setCustomMetadata(setting.namespace, setting.key, label, description)
                        showTeachDialog = false
                        reloadKnowledge()
                    }
                ) { Text("Save learning") }
            },
            dismissButton = {
                TextButton(onClick = { showTeachDialog = false }) { Text("Cancel") }
            }
        )
    }
}
