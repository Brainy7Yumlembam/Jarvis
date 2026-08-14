package co.aura.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.aura.presentation.theme.AuraBackgroundDark
import co.aura.presentation.theme.AuraPrimary
import co.aura.presentation.viewmodel.VoiceAssistantState
import co.aura.presentation.viewmodel.VoiceAssistantViewModel

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import co.aura.domain.model.ChatMessage
import co.aura.domain.model.MessageSender

import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun VoiceAssistantScreen(
    viewModel: VoiceAssistantViewModel,
    onRequestPermission: () -> Unit,
    onNavigateToMemory: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    val listState = rememberLazyListState()

    var hasScrolledInitially by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (!hasScrolledInitially) {
                listState.scrollToItem(messages.size - 1)
                hasScrolledInitially = true
            } else {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AuraBackgroundDark)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "J A R V I S",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AuraPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onNavigateToMemory,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2C4D))
                    ) {
                        Text("Memories", color = Color.White)
                    }
                    Button(
                        onClick = onNavigateToSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2C4D))
                    ) {
                        Text("Settings", color = Color.White)
                    }
                }
            }

            // Scrollable Conversation History
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF161525), shape = RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No active conversation turns yet.\nPress the microphone button to start talking to JARVIS.",
                                color = Color.Gray,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    items(messages) { msg ->
                        val isUser = msg.sender == MessageSender.USER
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                        ) {
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isUser) Color(0xFF3F3D56) else Color(0xFF26243A)
                                ),
                                modifier = Modifier.widthIn(max = 280.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = if (isUser) "You" else "JARVIS",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isUser) AuraPrimary else Color.LightGray
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = msg.content,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Footer controls block
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status indicator text
                val statusText = when (state) {
                    is VoiceAssistantState.Idle -> "JARVIS is idle"
                    is VoiceAssistantState.Listening -> "Listening..."
                    is VoiceAssistantState.Processing -> "Thinking..."
                    is VoiceAssistantState.Speaking -> "Speaking..."
                    is VoiceAssistantState.Error -> "System Alert"
                    is VoiceAssistantState.Paused -> "Paused"
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    when (state) {
                        is VoiceAssistantState.Processing -> {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AuraPrimary, strokeWidth = 2.dp)
                        }
                        is VoiceAssistantState.Speaking -> {
                            Text("🔊", style = MaterialTheme.typography.bodyMedium)
                        }
                        else -> {}
                    }
                }

                // Error text block
                if (state is VoiceAssistantState.Error) {
                    Text(
                        text = (state as VoiceAssistantState.Error).errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                // Partial transcript or query display for active state
                val activeText = when (val s = state) {
                    is VoiceAssistantState.Listening -> s.partialTranscript
                    is VoiceAssistantState.Processing -> s.userTranscript
                    else -> ""
                }
                if (activeText.isNotEmpty()) {
                    Text(
                        text = activeText,
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Control buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isListening = state is VoiceAssistantState.Listening
                    Button(
                        onClick = {
                            if (isListening) {
                                viewModel.stopListening()
                            } else {
                                onRequestPermission()
                                viewModel.startListening()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isListening) Color.Red else AuraPrimary
                        )
                    ) {
                        Text(
                            text = if (isListening) "Stop Mic" else "Talk to JARVIS",
                            color = Color.Black
                        )
                    }

                    if (state is VoiceAssistantState.Speaking) {
                        Button(
                            onClick = { viewModel.stopSpeaking() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                        ) {
                            Text("Stop Audio", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
