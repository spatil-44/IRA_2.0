package com.neurovox.ira

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import java.io.IOException
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.speech.SpeechRecognizer
import kotlinx.coroutines.launch

private val Ink = Color(0xFF10111B)
private val Panel = Color(0xFF191A27)
private val Muted = Color(0xFF9C9DAF)
private val Mint = Color(0xFF9CF5D5)
private val Lilac = Color(0xFFC1ADFF)
private val Warning = Color(0xFFFFB4A8)

class MainActivity : ComponentActivity() {
    private val accessibilityEnabled = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by IraSessionController.state.collectAsStateWithLifecycle()
            val voiceControlState by IraVoiceControlStatus.state.collectAsStateWithLifecycle()
            val isAccessibilityEnabled by accessibilityEnabled
            var datasetBusy by rememberSaveable { mutableStateOf(false) }
            var serverEndpoint by rememberSaveable {
                mutableStateOf(IraServerSettings.endpoint(this@MainActivity))
            }
            var serverEndpointStatus by rememberSaveable {
                mutableStateOf("Configure the Pi WebSocket address on the same Wi-Fi network.")
            }
            var datasetStatus by rememberSaveable {
                mutableStateOf("Training samples stay on this phone; they are never uploaded.")
            }
            var pendingDatasetLabel by rememberSaveable { mutableStateOf<String?>(null) }

            suspend fun saveDatasetSample(label: String) {
                datasetBusy = true
                datasetStatus = "Recording $label — say it now."
                try {
                    val path = WakeDatasetCollector.recordSample(this@MainActivity, label)
                    datasetStatus = "Saved sample on this phone: $path"
                } catch (error: IOException) {
                    datasetStatus = error.message ?: "Could not save the training sample."
                } catch (error: IllegalStateException) {
                    datasetStatus = error.message ?: "Could not record the training sample."
                } catch (error: SecurityException) {
                    datasetStatus = "Microphone permission is needed to collect samples."
                } finally {
                    datasetBusy = false
                }
            }

            val datasetPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                val label = pendingDatasetLabel
                pendingDatasetLabel = null
                if (granted && label != null) {
                    lifecycleScope.launch { saveDatasetSample(label) }
                } else if (!granted) {
                    datasetStatus = "Microphone permission is needed to collect samples."
                }
            }

            val microphonePermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    IraSessionController.start(this@MainActivity, serverEndpoint)
                } else {
                    IraSessionController.showError("Microphone permission is needed to record.")
                }
            }
            val wakeWordPermissions = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { grants ->
                val microphoneGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
                    grants[Manifest.permission.POST_NOTIFICATIONS] == true ||
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                if (microphoneGranted && notificationsGranted) {
                    startWakeWordListener()
                } else {
                    IraVoiceControlStatus.update(
                        false,
                        "Microphone and notification permissions are required for visible " +
                            "on-device wake-word listening.",
                    )
                }
            }
            val onWakeWordClick: () -> Unit = {
                if (voiceControlState.isListening) {
                    stopService(Intent(this@MainActivity, IraWakeWordService::class.java))
                } else if (state.isConnecting || state.isRecording ||
                    state.isWaitingForTranscript || datasetBusy
                ) {
                    IraVoiceControlStatus.updateMessage(
                        "Stop the current voice session or sample recording first.",
                    )
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    !SpeechRecognizer.isOnDeviceRecognitionAvailable(this@MainActivity)
                ) {
                    IraVoiceControlStatus.update(
                        false,
                        "This phone does not provide on-device speech recognition. " +
                            "Ira will not use a network recognizer.",
                    )
                } else {
                    val requestedPermissions = buildList {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.RECORD_AUDIO,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            add(Manifest.permission.RECORD_AUDIO)
                        }
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    if (requestedPermissions.isEmpty()) {
                        startWakeWordListener()
                    } else {
                        wakeWordPermissions.launch(requestedPermissions.toTypedArray())
                    }
                }
            }
            val onDatasetSample: (String) -> Unit = { label ->
                if (state.isConnecting || state.isRecording || state.isWaitingForTranscript ||
                    voiceControlState.isListening
                ) {
                    datasetStatus =
                        "Stop voice listening before collecting training samples."
                } else if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    lifecycleScope.launch { saveDatasetSample(label) }
                } else {
                    pendingDatasetLabel = label
                    datasetPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

            IraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Ink,
                ) {
                    IraHome(
                        state = state,
                        voiceControlState = voiceControlState,
                        accessibilityEnabled = isAccessibilityEnabled,
                        serverEndpoint = serverEndpoint,
                        serverEndpointStatus = serverEndpointStatus,
                        onServerEndpointChange = { serverEndpoint = it },
                        onSaveServerEndpoint = {
                            try {
                                IraServerSettings.saveEndpoint(
                                    this@MainActivity,
                                    serverEndpoint,
                                )
                                serverEndpoint = IraServerSettings.endpoint(this@MainActivity)
                                serverEndpointStatus =
                                    "Saved. Tap-to-talk audio will be sent to this Pi."
                            } catch (error: IllegalArgumentException) {
                                serverEndpointStatus =
                                    error.message ?: "Enter a valid Pi WebSocket address."
                            }
                        },
                        onRecordClick = {
                            if (voiceControlState.isListening) {
                                IraSessionController.showError(
                                    "Stop private wake-word listening before starting a voice session.",
                                )
                            } else if (state.isRecording) {
                                lifecycleScope.launch { IraSessionController.stop() }
                            } else if (ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    IraSessionController.start(this@MainActivity, serverEndpoint)
                            } else {
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onWakeWordClick = onWakeWordClick,
                        onEnableAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        datasetBusy = datasetBusy,
                        datasetStatus = datasetStatus,
                        onDatasetSample = onDatasetSample,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled.value = IraAccessibilityService.isEnabled(this)
    }

    private fun startWakeWordListener() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, IraWakeWordService::class.java)
                    .setAction(IraWakeWordService.ACTION_START),
            )
        } catch (error: SecurityException) {
            IraVoiceControlStatus.update(
                false,
                "Android did not allow the microphone service to start: " +
                    (error.message ?: "permission denied"),
            )
        }
    }

    override fun onDestroy() {
        IraSessionController.stopImmediately()
        super.onDestroy()
    }
}

@Composable
private fun IraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Mint,
            secondary = Lilac,
            background = Ink,
            surface = Panel,
            onSurface = Color.White,
        ),
        content = content,
    )
}

@Composable
private fun IraHome(
    state: IraSessionState,
    voiceControlState: IraVoiceControlState,
    accessibilityEnabled: Boolean,
    serverEndpoint: String,
    serverEndpointStatus: String,
    onServerEndpointChange: (String) -> Unit,
    onSaveServerEndpoint: () -> Unit,
    onRecordClick: () -> Unit,
    onWakeWordClick: () -> Unit,
    onEnableAccessibility: () -> Unit,
    datasetBusy: Boolean,
    datasetStatus: String,
    onDatasetSample: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1D1D30), Ink, Color(0xFF101A21)),
                ),
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Header()
        Spacer(Modifier.height(28.dp))
        Text(
            text = "YOUR VOICE,\nYOUR SPACE.",
            color = Color.White,
            fontSize = 34.sp,
            lineHeight = 39.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.8).sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Tap-to-talk sends audio to your Pi for transcription and replies.",
            color = Muted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(18.dp))
        IraServerConnectionCard(
            endpoint = serverEndpoint,
            status = serverEndpointStatus,
            onEndpointChange = onServerEndpointChange,
            onSave = onSaveServerEndpoint,
        )
        Spacer(Modifier.height(18.dp))
        IraVoiceControlCard(
            state = voiceControlState,
            accessibilityEnabled = accessibilityEnabled,
            enabled = voiceControlState.isListening ||
                (!state.isConnecting && !state.isRecording &&
                    !state.isWaitingForTranscript && !datasetBusy),
            onWakeWordClick = onWakeWordClick,
            onEnableAccessibility = onEnableAccessibility,
        )
        Spacer(Modifier.height(18.dp))
        ListeningCard(
            state = state,
            enabled = !voiceControlState.isListening,
            onRecordClick = onRecordClick,
        )
        Spacer(Modifier.height(18.dp))
        TranscriptCard(state = state)
        Spacer(Modifier.height(18.dp))
        AssistantReplyCard(state = state)
        Spacer(Modifier.height(18.dp))
        WakeDatasetCard(
            busy = datasetBusy,
            enabled = !state.isConnecting &&
                !state.isRecording &&
                !state.isWaitingForTranscript &&
                !voiceControlState.isListening,
            status = datasetStatus,
            onRecordSample = onDatasetSample,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "PI-POWERED SPEECH · LOCAL NETWORK",
            color = Muted.copy(alpha = 0.7f),
            fontSize = 10.sp,
            letterSpacing = 1.4.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun IraServerConnectionCard(
    endpoint: String,
    status: String,
    onEndpointChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(23.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(19.dp)) {
            Text(
                "RASPBERRY PI SERVER",
                color = Mint,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
            )
            Text(
                "Enter the Pi's LAN address. Use the same address and port 8765 " +
                    "in the ESP32 secrets.h file.",
                color = Color.White,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 9.dp),
            )
            OutlinedTextField(
                value = endpoint,
                onValueChange = onEndpointChange,
                label = { Text("WebSocket address") },
                placeholder = { Text("ws://192.168.1.50:8765/") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            Text(
                status,
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Use ws:// only on a trusted private Wi-Fi network; audio and transcripts are not encrypted.",
                color = Warning,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Save Pi address")
            }
        }
    }
}

@Composable
private fun AssistantReplyCard(state: IraSessionState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(23.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(19.dp)) {
            Text(
                "IRA’S REPLY",
                color = Mint,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
            )
            Text(
                text = state.assistantReply
                    ?: state.assistantError
                    ?: "Your assistant’s response will appear here.",
                color = when {
                    state.assistantError != null -> Warning
                    state.assistantReply != null -> Color.White
                    else -> Muted
                },
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun IraVoiceControlCard(
    state: IraVoiceControlState,
    accessibilityEnabled: Boolean,
    enabled: Boolean,
    onWakeWordClick: () -> Unit,
    onEnableAccessibility: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(23.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(19.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.RecordVoiceOver,
                    contentDescription = null,
                    tint = Mint,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "  PRIVATE DEVICE CONTROL",
                    color = Mint,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                )
            }
            Text(
                "Say “Ira” to control visible apps and open Android settings. " +
                    "Recognition and commands stay on this phone. A glowing orb appears " +
                    "over apps while it listens.",
                color = Color.White,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                if (accessibilityEnabled) {
                    "Android Accessibility control is enabled."
                } else {
                    "Enable Ira in Android Accessibility settings to control other apps."
                },
                color = if (accessibilityEnabled) Mint else Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                state.message,
                color = if (state.isListening) Mint else Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            Button(
                onClick = onEnableAccessibility,
                enabled = !accessibilityEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF302D43)),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) {
                Text(if (accessibilityEnabled) "Accessibility control enabled" else
                    "Enable app control")
            }
            Button(
                onClick = onWakeWordClick,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isListening) Color(0xFF31313F) else Mint,
                    contentColor = if (state.isListening) Color.White else Ink,
                    disabledContainerColor = Color(0xFF31313F),
                    disabledContentColor = Muted,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isListening) "Stop listening for Ira" else
                    "Start private wake-word listening")
            }
            Text(
                "Listening uses Android’s on-device recognizer and a visible notification. " +
                    "The microphone remains active until you stop it; no audio, transcript, " +
                    "or screen content is sent to Ira’s server in this mode.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun WakeDatasetCard(
    busy: Boolean,
    enabled: Boolean,
    status: String,
    onRecordSample: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171822)),
        shape = RoundedCornerShape(23.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(19.dp)) {
            Text(
                "TRAIN IRA’S WAKE WORD",
                color = Muted,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
            )
            Text(
                "Each tap records one second. Say the displayed label immediately after tapping.",
                color = Color.White,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 9.dp),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onRecordSample("ira") },
                enabled = enabled && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Record “Ira” wake-word sample")
            }
            Button(
                onClick = { onRecordSample("unknown") },
                enabled = enabled && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF302D43)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Record other speech")
            }
            Button(
                onClick = { onRecordSample("noise") },
                enabled = enabled && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF302D43)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Record background noise")
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 10.dp)
                        .size(24.dp),
                    color = Mint,
                    strokeWidth = 2.dp,
                )
            }
            Text(
                status,
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                "Saved only on this phone. Samples are not sent to the server.",
                color = Mint,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(Mint, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("i", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.padding(start = 11.dp)) {
                Text("IRA", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("NEUROVOX", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.06f), CircleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(Mint, CircleShape),
            )
            Text(
                "LOCAL MODE",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 7.dp),
            )
        }
    }
}

@Composable
private fun ListeningCard(
    state: IraSessionState,
    enabled: Boolean,
    onRecordClick: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "listening-pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (state.isRecording) 1.08f else 1.025f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("VOICE SESSION", color = Muted, fontSize = 10.sp, letterSpacing = 1.7.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = if (state.isRecording) Mint else Muted,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        if (state.isConnecting) " STARTING"
                        else if (state.isRecording) " LISTENING"
                        else if (state.isWaitingForTranscript) " THINKING"
                        else " READY",
                        color = if (state.isRecording) Mint else Muted,
                        fontSize = 9.sp,
                        letterSpacing = 0.8.sp,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .size(156.dp)
                    .scale(pulse)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                if (state.isRecording) Mint.copy(alpha = 0.25f)
                                else Lilac.copy(alpha = 0.2f),
                                Color(0xFF333047).copy(alpha = 0.55f),
                                Color.Transparent,
                            ),
                        ),
                        CircleShape,
                    )
                    .border(
                        width = 1.dp,
                        color = (if (state.isRecording) Mint else Lilac).copy(alpha = 0.35f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .background(Color(0xFF28283A), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isConnecting || state.isWaitingForTranscript) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(39.dp),
                            color = Mint,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isRecording) Icons.Default.GraphicEq else Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (state.isRecording) Mint else Lilac,
                            modifier = Modifier.size(39.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = when {
                    state.isConnecting -> "Connecting to the Pi"
                    state.isWaitingForTranscript && state.transcript == null ->
                        "Transcribing on the Pi"
                    state.isWaitingForTranscript -> "Waiting for the Pi"
                    state.isRecording -> "I’m listening"
                    else -> "Ready when you are"
                },
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = when {
                    state.isRecording -> "Tap stop when you’re finished."
                    state.isWaitingForTranscript ->
                        "Your audio is being processed by the configured Pi server."
                    else -> "Tap below and speak; your audio will go to the configured Pi."
                },
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (state.errorMessage != null) {
                Text(
                    text = state.errorMessage,
                    color = Warning,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onRecordClick,
                enabled = enabled && !state.isConnecting && !state.isWaitingForTranscript,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isRecording) Color(0xFF31313F) else Mint,
                    contentColor = if (state.isRecording) Color.White else Ink,
                    disabledContainerColor = Color(0xFF31313F),
                    disabledContentColor = Muted,
                ),
                shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Icon(
                    imageVector = if (state.isRecording) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    if (state.isRecording) "  Stop recording" else "  Start talking",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun TranscriptCard(state: IraSessionState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171822)),
        shape = RoundedCornerShape(23.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(19.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = Mint,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "  LATEST TRANSCRIPT",
                    color = Muted,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.weight(1f))
                state.latencyMs?.let {
                    Text("${it.toInt()} ms", color = Muted, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = state.transcript ?: "Your words will appear here after you speak.",
                color = if (state.transcript == null) Muted else Color.White,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            )
        }
    }
}
