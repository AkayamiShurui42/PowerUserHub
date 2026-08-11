package com.poweruserhub.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poweruserhub.app.service.ShellService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedDeveloperModeScreen(shellService: ShellService) {
    val coroutineScope = rememberCoroutineScope()
    var isAdvancedEnabled by remember { mutableStateOf(false) }
    var commandInput by remember { mutableStateOf("") }
    val consoleLogs = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val isPrivileged = shellService.isPrivilegedActive()

    LaunchedEffect(consoleLogs.size) {
        if (consoleLogs.isNotEmpty()) {
            listState.animateScrollToItem(consoleLogs.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Toggle Switch for safety
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Developer Console Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Direct interface for shell execution commands.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Switch(
                checked = isAdvancedEnabled,
                onCheckedChange = { isAdvancedEnabled = it }
            )
        }

        HorizontalDivider()

        if (!isAdvancedEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Advanced Terminal Mode Disabled",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enable Developer Console Mode toggle to unlock raw operations like settings keys, pm tools, and intent broadcasts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        lineHeight = 20.sp
                    )
                }
            }
        } else {
            // Console output logs
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(Color(0xFF0F0B1E), shape = RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (consoleLogs.isEmpty()) {
                    item {
                        Text(
                            text = "Terminal initialized. Ready for input.\nType commands or use presets below.",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
                        )
                    }
                }
                items(consoleLogs) { log ->
                    Text(
                        text = log,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = if (log.startsWith("$")) MaterialTheme.colorScheme.secondary 
                                else if (log.startsWith("Error:")) MaterialTheme.colorScheme.error
                                else Color.White
                    )
                }
            }

            // Presets row
            Text(
                text = "Command Presets",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val presets = listOf("settings list global", "pm list packages", "dumpsys battery")
                presets.forEach { preset ->
                    OutlinedButton(
                        onClick = { commandInput = preset },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(preset.split(" ")[0] + " " + preset.split(" ").getOrNull(1), fontSize = 10.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Command input row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    placeholder = { Text("Enter shell command...") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                
                FloatingActionButton(
                    onClick = {
                        if (commandInput.trim().isNotEmpty()) {
                            val cmd = commandInput.trim()
                            consoleLogs.add("$ $cmd")
                            while (consoleLogs.size > 200) {
                                consoleLogs.removeAt(0)
                            }
                            commandInput = ""
                            coroutineScope.launch {
                                val result = shellService.executeCommand(cmd)
                                if (result.isSuccess) {
                                    if (result.stdout.isNotEmpty()) {
                                        consoleLogs.add(result.stdout)
                                    } else {
                                        consoleLogs.add("[Success, no output]")
                                    }
                                } else {
                                    consoleLogs.add("Error: ${result.stderr}")
                                }
                                while (consoleLogs.size > 200) {
                                    consoleLogs.removeAt(0)
                                }
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Send, contentDescription = null)
                }
            }
        }
    }
}
