package co.aura.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import co.aura.presentation.theme.AuraBackgroundDark
import co.aura.presentation.theme.AuraPrimary
import co.aura.presentation.theme.AuraSurfaceDark
import co.aura.presentation.viewmodel.SettingsAction
import co.aura.presentation.viewmodel.SettingsViewModel
import co.aura.ai.AiProviderType
import co.aura.conversation.ConversationMode
import org.koin.compose.getKoin
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToMemory: () -> Unit
) {
    val koin = getKoin()
    val viewModel: SettingsViewModel = viewModel {
        koin.get<SettingsViewModel>()
    }
    val state by viewModel.uiState.collectAsState()
    var apiKeyInput by remember { mutableStateOf("") }
    var showSaveMessage by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("System Settings", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AuraBackgroundDark
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AuraBackgroundDark)
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Memory Settings Section
            Card(
                colors = CardDefaults.cardColors(containerColor = AuraSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Memory Configuration",
                        style = MaterialTheme.typography.titleMedium,
                        color = AuraPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "View and manage what JARVIS remembers about you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onNavigateToMemory,
                        colors = ButtonDefaults.buttonColors(containerColor = AuraPrimary, contentColor = Color.Black)
                    ) {
                        Text("Manage Saved Memories")
                    }
                }
            }

            // API configuration Section
            Card(
                colors = CardDefaults.cardColors(containerColor = AuraSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Development / API Configuration",
                        style = MaterialTheme.typography.titleMedium,
                        color = AuraPrimary
                    )
                    
                    // Config Status Display
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (state.isKeyConfigured) "Gemini API: Configured" else "Gemini API: Not configured",
                            color = if (state.isKeyConfigured) Color.Green else Color.Red,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // TextField for Entering the Key
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("Gemini API Key", color = Color.Gray) },
                        placeholder = { Text("AIzaSy...", color = Color.DarkGray) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AuraPrimary,
                            unfocusedBorderColor = Color.Gray,
                            cursorColor = AuraPrimary
                        ),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )

                    // Error/Success Message Display
                    if (showSaveMessage) {
                        Text(
                            text = "Key saved successfully!",
                            color = Color.Green,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // Save / Clear Key options
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (apiKeyInput.isNotBlank()) {
                                    viewModel.onEvent(SettingsAction.SaveKey(apiKeyInput))
                                    apiKeyInput = "" // Clean local input immediately to follow security specs
                                    showSaveMessage = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AuraPrimary,
                                contentColor = Color.Black
                            ),
                            enabled = apiKeyInput.isNotBlank()
                        ) {
                            Text("Save Key")
                        }

                        Button(
                            onClick = {
                                viewModel.onEvent(SettingsAction.ClearKey)
                                apiKeyInput = ""
                                showSaveMessage = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE53935),
                                contentColor = Color.White
                            ),
                            enabled = state.isKeyConfigured
                        ) {
                            Text("Clear Key")
                        }
                    }
                }
            }

            // Gemini API Projects Section
            Card(
                colors = CardDefaults.cardColors(containerColor = AuraSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Gemini API Projects",
                        style = MaterialTheme.typography.titleMedium,
                        color = AuraPrimary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Active Brain: ${state.activeGeminiProject}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Configured Projects: ${state.configuredProjectsCount} / 10",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    HorizontalDivider(color = Color.DarkGray)

                    var showEditDialogForSlot by remember { mutableStateOf<co.aura.ai.GeminiCredential?>(null) }

                    state.geminiCredentials.forEach { cred ->
                        val formattedSlot = if (cred.slot < 10) "0${cred.slot}" else "${cred.slot}"
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "$formattedSlot  ${cred.label}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    val statusColor = when (cred.status) {
                                        co.aura.ai.CredentialStatus.ACTIVE -> AuraPrimary
                                        co.aura.ai.CredentialStatus.AVAILABLE -> Color(0xFF4CAF50)
                                        co.aura.ai.CredentialStatus.COOLDOWN -> Color(0xFFFF9800)
                                        co.aura.ai.CredentialStatus.DISABLED -> Color.Gray
                                        co.aura.ai.CredentialStatus.INVALID -> Color(0xFFF44336)
                                        else -> Color.DarkGray
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(statusColor, shape = androidx.compose.foundation.shape.CircleShape)
                                        )
                                        Text(
                                            text = cred.status.name,
                                            color = statusColor,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (cred.status == co.aura.ai.CredentialStatus.EMPTY) {
                                        Button(
                                            onClick = { showEditDialogForSlot = cred },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = AuraPrimary,
                                                contentColor = Color.Black
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("Add API Key", style = MaterialTheme.typography.bodySmall)
                                        }
                                    } else {
                                        Button(
                                            onClick = { showEditDialogForSlot = cred },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color.DarkGray,
                                                contentColor = Color.White
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("Edit", style = MaterialTheme.typography.bodySmall)
                                        }
                                        Button(
                                            onClick = {
                                                viewModel.onEvent(SettingsAction.RemoveProjectCredential(cred.slot))
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFFE53935),
                                                contentColor = Color.White
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("Remove", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }

                            if (cred.status != co.aura.ai.CredentialStatus.EMPTY && cred.status != co.aura.ai.CredentialStatus.COOLDOWN) {
                                val keyPrefix = cred.apiKey?.take(4) ?: ""
                                val maskedKey = if (keyPrefix.isNotEmpty()) "$keyPrefix...XXXX" else ""
                                if (maskedKey.isNotEmpty()) {
                                    Text(
                                        text = maskedKey,
                                        color = Color.LightGray,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(start = 24.dp)
                                    )
                                }
                            } else if (cred.status == co.aura.ai.CredentialStatus.COOLDOWN) {
                                Text(
                                    text = "Available again later",
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 24.dp)
                                )
                            }
                            HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }

                    showEditDialogForSlot?.let { cred ->
                        var labelInput by remember { mutableStateOf(if (cred.status == co.aura.ai.CredentialStatus.EMPTY) "Gemini Project ${if (cred.slot < 10) "0${cred.slot}" else "${cred.slot}"}" else cred.label) }
                        var apiKeyInputText by remember { mutableStateOf("") }
                        var enabledChecked by remember { mutableStateOf(cred.enabled) }

                        AlertDialog(
                            onDismissRequest = { showEditDialogForSlot = null },
                            title = {
                                Text(
                                    text = "Configure Slot ${if (cred.slot < 10) "0${cred.slot}" else "${cred.slot}"}",
                                    color = Color.White
                                )
                            },
                            text = {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedTextField(
                                        value = labelInput,
                                        onValueChange = { labelInput = it },
                                        label = { Text("Label") },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        )
                                    )

                                    OutlinedTextField(
                                        value = apiKeyInputText,
                                        onValueChange = { apiKeyInputText = it },
                                        label = {
                                            Text(
                                                if (cred.status == co.aura.ai.CredentialStatus.EMPTY) "API Key"
                                                else "API Key (leave blank to keep unchanged)"
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        visualTransformation = PasswordVisualTransformation()
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Checkbox(
                                            checked = enabledChecked,
                                            onCheckedChange = { enabledChecked = it },
                                            colors = CheckboxDefaults.colors(checkedColor = AuraPrimary)
                                        )
                                        Text("Enabled", color = Color.White)
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        val finalKey = if (apiKeyInputText.isBlank()) cred.apiKey else apiKeyInputText
                                        viewModel.onEvent(
                                            SettingsAction.SaveProjectCredential(
                                                slot = cred.slot,
                                                label = labelInput,
                                                apiKey = finalKey,
                                                enabled = enabledChecked
                                            )
                                        )
                                        showEditDialogForSlot = null
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AuraPrimary,
                                        contentColor = Color.Black
                                    )
                                ) {
                                    Text("Save")
                                }
                            },
                            dismissButton = {
                                Button(
                                    onClick = { showEditDialogForSlot = null },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.DarkGray,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text("Cancel")
                                }
                            },
                            containerColor = AuraSurfaceDark
                        )
                    }
                }
            }

            // AI Engine Settings Section
            Card(
                colors = CardDefaults.cardColors(containerColor = AuraSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "AI Engine",
                        style = MaterialTheme.typography.titleMedium,
                        color = AuraPrimary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                viewModel.onEvent(SettingsAction.UpdateAiProvider(AiProviderType.GEMINI))
                            }
                        ) {
                            RadioButton(
                                selected = state.aiProviderType == AiProviderType.GEMINI,
                                onClick = {
                                    viewModel.onEvent(SettingsAction.UpdateAiProvider(AiProviderType.GEMINI))
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = AuraPrimary)
                            )
                            Text("Gemini", color = Color.White)
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                viewModel.onEvent(SettingsAction.UpdateAiProvider(AiProviderType.LOCAL))
                            }
                        ) {
                            RadioButton(
                                selected = state.aiProviderType == AiProviderType.LOCAL,
                                onClick = {
                                    viewModel.onEvent(SettingsAction.UpdateAiProvider(AiProviderType.LOCAL))
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = AuraPrimary)
                            )
                            Text("Local AI (Coming Soon)", color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    if (state.aiProviderType == AiProviderType.GEMINI) {
                        Text(
                            text = "Gemini AI is active.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    } else {
                        Text(
                            text = "Local AI is not available on this device yet.\nJARVIS will continue using Gemini.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                }
            }

            // Voice & Speech Settings Section
            Card(
                colors = CardDefaults.cardColors(containerColor = AuraSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Voice & Speech",
                        style = MaterialTheme.typography.titleMedium,
                        color = AuraPrimary
                    )

                    // Profile & Engine Display
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Voice Profile:", color = Color.White)
                        Text(state.selectedVoice, color = AuraPrimary)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Selected Voice:", color = Color.White)
                        Text(state.actualSelectedVoice, color = AuraPrimary)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Engine:", color = Color.White)
                        Text("System TTS", color = Color.Gray)
                    }

                    // Language Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Language:", color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val isUk = state.selectedLanguage == "en-GB"
                            Button(
                                onClick = { viewModel.onEvent(SettingsAction.UpdateLanguage("en-GB")) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isUk) AuraPrimary else Color(0xFF2E2C4D),
                                    contentColor = if (isUk) Color.Black else Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("English (UK)")
                            }
                            Button(
                                onClick = { viewModel.onEvent(SettingsAction.UpdateLanguage("en-US")) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!isUk) AuraPrimary else Color(0xFF2E2C4D),
                                    contentColor = if (!isUk) Color.Black else Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("English (US)")
                            }
                        }
                    }

                    // Speech Rate Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Speech Rate:", color = Color.White)
                            Text("${(state.speechRate * 10).toInt() / 10.0}x", color = AuraPrimary)
                        }
                        Slider(
                            value = state.speechRate,
                            onValueChange = { viewModel.onEvent(SettingsAction.UpdateSpeechRate(it)) },
                            valueRange = 0.5f..2.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = AuraPrimary,
                                activeTrackColor = AuraPrimary
                            )
                        )
                    }

                    // Pitch Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Pitch:", color = Color.White)
                            Text("${(state.pitch * 10).toInt() / 10.0}x", color = AuraPrimary)
                        }
                        Slider(
                            value = state.pitch,
                            onValueChange = { viewModel.onEvent(SettingsAction.UpdatePitch(it)) },
                            valueRange = 0.5f..2.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = AuraPrimary,
                                activeTrackColor = AuraPrimary
                            )
                        )
                    }

                    // Continuous Conversation Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Continuous Conversation:", color = Color.White)
                        Switch(
                            checked = state.isContinuousConversation,
                            onCheckedChange = { viewModel.onEvent(SettingsAction.UpdateContinuousConversation(it)) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AuraPrimary,
                                checkedTrackColor = AuraPrimary.copy(alpha = 0.5f)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Conversation Mode Selector
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Conversation Mode:", color = Color.White)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val currentMode = state.selectedConversationMode
                            ConversationMode.values().forEach { mode ->
                                val isSelected = currentMode == mode
                                Button(
                                    onClick = { viewModel.onEvent(SettingsAction.UpdateConversationMode(mode)) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) AuraPrimary else Color(0xFF2E2C4D),
                                        contentColor = if (isSelected) Color.Black else Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.weight(1f).height(36.dp)
                                ) {
                                    Text(
                                        text = when (mode) {
                                            ConversationMode.COMMAND -> "Command"
                                            ConversationMode.CONVERSATION -> "Conversation"
                                            ConversationMode.HYBRID -> "Hybrid"
                                        },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Test Voice Button
                    Button(
                        onClick = { viewModel.onEvent(SettingsAction.SpeakTestVoice) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuraPrimary,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Test Voice")
                    }
                }
            }
        }
    }
}
