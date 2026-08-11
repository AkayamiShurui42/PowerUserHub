package com.poweruserhub.app.ui.screens

import android.widget.Toast
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
import com.poweruserhub.app.model.SettingLock
import com.poweruserhub.app.service.LockDatabaseHelper
import com.poweruserhub.app.service.ShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockedSettingsScreen(
    dbHelper: LockDatabaseHelper,
    shellService: ShellService
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var locksList by remember { mutableStateOf<List<SettingLock>>(emptyList()) }
    var isChecking by remember { mutableStateOf(false) }

    fun refreshLocks() {
        locksList = dbHelper.getAllLocks()
    }

    LaunchedEffect(Unit) {
        refreshLocks()
    }

    fun verifyAndRestoreLocks() {
        isChecking = true
        coroutineScope.launch(Dispatchers.Default) {
            val sdf = SimpleDateFormat("MMM dd, hh:mm:ss a", Locale.getDefault())
            val activeLocks = dbHelper.getAllLocks().filter { it.isEnabled }
            
            for (lock in activeLocks) {
                try {
                    val currentValue = shellService.readSetting(lock.namespace, lock.key)
                    val timestamp = sdf.format(Date())
                    if (currentValue == lock.desiredValue) {
                        dbHelper.updateLockStatus(lock.key, "Verified", timestamp)
                    } else {
                        val writeResult = shellService.writeSetting(lock.namespace, lock.key, lock.desiredValue)
                        val newValue = shellService.readSetting(lock.namespace, lock.key)
                        if (newValue == lock.desiredValue) {
                            dbHelper.updateLockStatus(lock.key, "Restored", timestamp)
                        } else {
                            val status = if (writeResult.isSuccess) "Failed (OS protected)" else "Failed (${writeResult.stderr.take(20)})"
                            dbHelper.updateLockStatus(lock.key, status, timestamp)
                        }
                    }
                } catch (e: Exception) {
                    val timestamp = sdf.format(Date())
                    dbHelper.updateLockStatus(lock.key, "Error: ${e.message?.take(20)}", timestamp)
                }
            }
            
            withContext(Dispatchers.Main) {
                refreshLocks()
                isChecking = false
                Toast.makeText(context, "Verification complete.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Enforced System Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Button(
                onClick = { verifyAndRestoreLocks() },
                enabled = !isChecking,
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Verify Now", fontSize = 12.sp)
                }
            }
        }

        if (locksList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No locked settings configured.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Browse settings and choose 'Lock Value' to enforce them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(locksList) { lock ->
                    LockRowCard(
                        lock = lock,
                        onDelete = {
                            dbHelper.deleteLock(lock.key)
                            refreshLocks()
                            Toast.makeText(context, "Removed lock for ${lock.key}.", Toast.LENGTH_SHORT).show()
                        },
                        onToggle = {
                            val updated = lock.copy(isEnabled = !lock.isEnabled)
                            dbHelper.insertOrUpdateLock(updated)
                            refreshLocks()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun LockRowCard(
    lock: SettingLock,
    onDelete: () -> Unit,
    onToggle: () -> Unit
) {
    val statusColor = when {
        !lock.isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        lock.status == "Verified" -> MaterialTheme.colorScheme.secondary
        lock.status == "Restored" -> MaterialTheme.colorScheme.primary
        lock.status?.startsWith("Failed") == true -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (lock.isEnabled) MaterialTheme.colorScheme.surface 
                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = lock.key,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (lock.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "Namespace: ${lock.namespace}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
                Switch(
                    checked = lock.isEnabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Enforced Value",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        text = lock.desiredValue,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Verification State",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        text = if (lock.isEnabled) (lock.status ?: "Pending") else "Disabled",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor
                    )
                }
            }

            if (lock.isEnabled && lock.lastVerified != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Checked: ${lock.lastVerified}",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.align(Alignment.End)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Unlock & Delete Rule", fontSize = 12.sp)
            }
        }
    }
}
