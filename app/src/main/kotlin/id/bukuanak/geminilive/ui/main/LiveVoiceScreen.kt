package id.bukuanak.geminilive.ui.main

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import id.bukuanak.geminilive.audio.AudioEffectsDiagnostics
import id.bukuanak.geminilive.data.AppSettings
import id.bukuanak.geminilive.live.LiveConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveVoiceScreen(
    viewModel: LiveVoiceViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Live Visualizer & Status, 1: AEC Diagnostics, 2: Event Log

    // Microphone permission launcher
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (isGranted) {
            viewModel.startSession()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearErrorMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Gemini Live Voice", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        ConnectionStatusBadge(uiState.connectionState)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.openSettings() }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Tab Selector
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Conversation") },
                    icon = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("AEC Pipeline") },
                    icon = { Icon(Icons.Default.Security, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Event Log") },
                    icon = { Icon(Icons.Default.GraphicEq, contentDescription = null) }
                )
            }

            when (selectedTab) {
                0 -> ConversationTab(
                    uiState = uiState,
                    onConnectToggle = {
                        if (uiState.connectionState == LiveConnectionState.DISCONNECTED ||
                            uiState.connectionState == LiveConnectionState.ERROR
                        ) {
                            if (hasMicPermission) {
                                viewModel.startSession()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            viewModel.disconnect()
                        }
                    },
                    onMuteToggle = { viewModel.toggleMute() },
                    onBargeIn = { viewModel.manualBargeIn() },
                    onClearTranscript = { viewModel.clearTranscript() }
                )
                1 -> AecDiagnosticsTab(
                    diagnostics = uiState.audioDiagnostics,
                    enableSoftwareGate = uiState.config.enableSoftwareEchoGate,
                    bargeInCount = uiState.bargeInCount,
                    lastBargeIn = uiState.lastBargeInTime
                )
                2 -> EventLogTab(
                    logs = uiState.logs,
                    onClearLogs = { viewModel.clearLogs() }
                )
            }
        }
    }

    if (uiState.isSettingsOpen) {
        SettingsDialog(
            currentConfig = uiState.config,
            onDismiss = { viewModel.closeSettings() },
            onSave = { apiKey, model, voice, aec, gate, prompt ->
                viewModel.saveSettings(apiKey, model, voice, aec, gate, prompt)
            }
        )
    }
}

@Composable
fun ConversationTab(
    uiState: LiveUiState,
    onConnectToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onBargeIn: () -> Unit,
    onClearTranscript: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Barge-in alert notification card
        AnimatedVisibility(visible = uiState.bargeInCount > 0) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Barge-in Interrupts: ${uiState.bargeInCount}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    uiState.lastBargeInTime?.let {
                        Text(
                            text = "Last: $it",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // Voice Orb Visualizer
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            DualAudioOrb(
                userRms = uiState.userMicRms,
                modelRms = uiState.geminiSpeakerRms,
                isGeminiSpeaking = uiState.isGeminiSpeaking,
                connectionState = uiState.connectionState,
                isMuted = uiState.isMuted
            )
        }

        // Dynamic State Prompt
        Text(
            text = when (uiState.connectionState) {
                LiveConnectionState.DISCONNECTED -> "Tap Connect to begin real-time voice conversation"
                LiveConnectionState.CONNECTING -> "Connecting to Gemini Live WebSocket..."
                LiveConnectionState.CONNECTED -> if (uiState.isMuted) "Microphone Muted" else "Listening... Speak naturally"
                LiveConnectionState.SPEAKING -> "Gemini is speaking (Speak anytime to barge in!)"
                LiveConnectionState.ERROR -> "Connection error. Check settings and API key."
            },
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // Live Transcript Box
        if (uiState.transcript.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .padding(bottom = 12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Spoken Output", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = onClearTranscript, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                        }
                    }
                    Text(
                        text = uiState.transcript,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Bottom Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mute Button
            IconButton(
                onClick = onMuteToggle,
                enabled = uiState.connectionState != LiveConnectionState.DISCONNECTED,
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        if (uiState.isMuted) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    )
            ) {
                Icon(
                    if (uiState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Mute",
                    tint = if (uiState.isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Primary Connect / Disconnect Action
            val isConnected = uiState.connectionState != LiveConnectionState.DISCONNECTED &&
                    uiState.connectionState != LiveConnectionState.ERROR

            FloatingActionButton(
                onClick = onConnectToggle,
                containerColor = if (isConnected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    if (isConnected) Icons.Default.CallEnd else Icons.Default.Call,
                    contentDescription = if (isConnected) "Disconnect" else "Connect",
                    modifier = Modifier.size(32.dp)
                )
            }

            // Instant Barge-In / Interrupt Button
            IconButton(
                onClick = onBargeIn,
                enabled = uiState.isGeminiSpeaking,
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        if (uiState.isGeminiSpeaking) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        CircleShape
                    )
            ) {
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = "Interrupt",
                    tint = if (uiState.isGeminiSpeaking) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
fun DualAudioOrb(
    userRms: Float,
    modelRms: Float,
    isGeminiSpeaking: Boolean,
    connectionState: LiveConnectionState,
    isMuted: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idleScale"
    )

    // User mic scale (expands with user speech volume)
    val userScale = 1f + (userRms * 1.5f).coerceIn(0f, 0.8f)

    // Model speaker scale (expands with Gemini speech volume)
    val modelScale = if (isGeminiSpeaking) 1f + (modelRms * 1.8f).coerceIn(0f, 0.9f) else idlePulse

    val outerColor by animateColorAsState(
        targetValue = when {
            isMuted -> Color(0xFFE57373)
            userRms > 0.05f -> Color(0xFF4CAF50)
            connectionState == LiveConnectionState.CONNECTED -> Color(0xFF64B5F6)
            connectionState == LiveConnectionState.SPEAKING -> Color(0xFFBA68C8)
            else -> Color(0xFF9E9E9E)
        },
        label = "outerColor"
    )

    Box(contentAlignment = Alignment.Center) {
        // Outer Mic Ring (expands when user speaks)
        Box(
            modifier = Modifier
                .size(240.dp)
                .scale(userScale)
                .clip(CircleShape)
                .border(3.dp, outerColor.copy(alpha = 0.6f), CircleShape)
        )

        // Middle Glow
        Box(
            modifier = Modifier
                .size(190.dp)
                .scale(modelScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            if (isGeminiSpeaking) Color(0xFF9C27B0).copy(alpha = 0.7f) else Color(0xFF2196F3).copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Inner Core Orb
        Box(
            modifier = Modifier
                .size(130.dp)
                .scale(if (isGeminiSpeaking) modelScale else 1f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = if (isGeminiSpeaking) {
                            listOf(Color(0xFFE1BEE7), Color(0xFFAB47BC), Color(0xFF6A1B9A))
                        } else {
                            listOf(Color(0xFFBBDEFB), Color(0xFF42A5F5), Color(0xFF1565C0))
                        }
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isGeminiSpeaking) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.Mic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

@Composable
fun ConnectionStatusBadge(state: LiveConnectionState) {
    val (color, text) = when (state) {
        LiveConnectionState.DISCONNECTED -> Color.Gray to "Offline"
        LiveConnectionState.CONNECTING -> Color(0xFFFFA000) to "Connecting"
        LiveConnectionState.CONNECTED -> Color(0xFF4CAF50) to "Live"
        LiveConnectionState.SPEAKING -> Color(0xFF9C27B0) to "Speaking"
        LiveConnectionState.ERROR -> Color(0xFFF44336) to "Error"
    }

    Surface(
        color = color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
fun AecDiagnosticsTab(
    diagnostics: AudioEffectsDiagnostics?,
    enableSoftwareGate: Boolean,
    bargeInCount: Int,
    lastBargeIn: String?
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Acoustic Echo Cancellation (AEC) Architecture",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "To prevent Gemini's voice output from looping back through the microphone, Android utilizes hardware DSP echo cancellation and VoIP audio routing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Hardware Audio Effects", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    DiagnosticRow(
                        title = "Hardware Acoustic Echo Canceler (AEC)",
                        status = if (diagnostics?.aecAvailable == true) "Supported" else "Not Available",
                        isActive = diagnostics?.aecEnabled == true
                    )
                    DiagnosticRow(
                        title = "Hardware Noise Suppressor (NS)",
                        status = if (diagnostics?.nsAvailable == true) "Supported" else "Not Available",
                        isActive = diagnostics?.nsEnabled == true
                    )
                    DiagnosticRow(
                        title = "Hardware Automatic Gain Control (AGC)",
                        status = if (diagnostics?.agcAvailable == true) "Supported" else "Not Available",
                        isActive = diagnostics?.agcEnabled == true
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Audio Pipeline & Routing", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    PipelineItem("Audio Manager Mode", "MODE_IN_COMMUNICATION")
                    PipelineItem("Mic AudioSource", "VOICE_COMMUNICATION (HAL AEC Ref)")
                    PipelineItem("Mic Stream", "16,000 Hz, 16-bit Mono PCM (100ms chunks)")
                    PipelineItem("Speaker AudioTrack", "24,000 Hz, 16-bit Mono PCM (USAGE_VOICE_COMMUNICATION)")
                    PipelineItem(
                        "Software Echo Gate",
                        if (enableSoftwareGate) "Active (Defensive suppression)" else "Disabled (Hardware AEC Only)"
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Barge-In (Cutoff) Metrics", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    PipelineItem("Server Interruption Detection", "Gemini Live API (interrupted: true)")
                    PipelineItem("Client Flush Latency", "< 10 ms (AudioTrack.pause & flush)")
                    PipelineItem("Barge-In Events Triggered", "$bargeInCount times")
                    if (lastBargeIn != null) {
                        PipelineItem("Last Interruption Timestamp", lastBargeIn)
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticRow(title: String, status: String, isActive: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
        }
        Icon(
            if (isActive) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (isActive) Color(0xFF4CAF50) else Color(0xFFFFA000),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun PipelineItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun EventLogTab(
    logs: List<LogEntry>,
    onClearLogs: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Real-Time Event Stream", fontWeight = FontWeight.Bold)
            TextButton(onClick = onClearLogs) {
                Text("Clear")
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No events recorded yet. Connect to start session.", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(logs) { log ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                log.isBargeIn -> MaterialTheme.colorScheme.tertiaryContainer
                                log.isError -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceContainer
                            }
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = log.time,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(end = 8.dp, top = 2.dp)
                            )
                            Text(
                                text = log.message,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = when {
                                    log.isBargeIn -> MaterialTheme.colorScheme.onTertiaryContainer
                                    log.isError -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    currentConfig: id.bukuanak.geminilive.data.AppConfig,
    onDismiss: () -> Unit,
    onSave: (apiKey: String, model: String, voice: String, aec: Boolean, gate: Boolean, prompt: String) -> Unit
) {
    var apiKey by remember { mutableStateOf(currentConfig.apiKey) }
    var selectedModel by remember { mutableStateOf(currentConfig.model) }
    var selectedVoice by remember { mutableStateOf(currentConfig.voice) }
    var hardwareAec by remember { mutableStateOf(currentConfig.enableHardwareAec) }
    var softwareEchoGate by remember { mutableStateOf(currentConfig.enableSoftwareEchoGate) }
    var systemPrompt by remember { mutableStateOf(currentConfig.systemPrompt) }
    var showApiKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gemini Live Settings") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("AIzaSy...") },
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Default.Clear else Icons.Default.Security,
                                    contentDescription = "Toggle visibility"
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Text("Voice Selection", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AppSettings.AVAILABLE_VOICES.forEach { voice ->
                            FilterChip(
                                selected = selectedVoice == voice,
                                onClick = { selectedVoice = voice },
                                label = { Text(voice, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                item {
                    Text("Model Selection", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    AppSettings.AVAILABLE_MODELS.forEach { model ->
                        FilterChip(
                            selected = selectedModel == model,
                            onClick = { selectedModel = model },
                            label = { Text(model.removePrefix("models/"), fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hardware AEC", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Acoustic Echo Canceler on AudioRecord", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(checked = hardwareAec, onCheckedChange = { hardwareAec = it })
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Software Echo Gate", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Extra suppression during speaker output", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(checked = softwareEchoGate, onCheckedChange = { softwareEchoGate = it })
                    }
                }

                item {
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        label = { Text("System Instructions") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(apiKey, selectedModel, selectedVoice, hardwareAec, softwareEchoGate, systemPrompt)
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
