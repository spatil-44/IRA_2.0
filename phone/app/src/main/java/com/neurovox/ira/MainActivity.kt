package com.neurovox.ira

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private val Ink = Color(0xFF10111B)
private val Panel = Color(0xFF191A27)
private val Muted = Color(0xFF9C9DAF)
private val Mint = Color(0xFF9CF5D5)
private val Lilac = Color(0xFFC1ADFF)
private val Warning = Color(0xFFFFB4A8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("ira-phone", MODE_PRIVATE)
        IraSessionController.restoreServerUrl(preferences.getString("server_url", "") ?: "")

        setContent {
            val state by IraSessionController.state.collectAsStateWithLifecycle()
            var serverUrl by rememberSaveable {
                mutableStateOf(IraSessionController.savedServerUrl())
            }
            val microphonePermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    lifecycleScope.launch {
                        IraSessionController.start(this@MainActivity, serverUrl)
                    }
                } else {
                    IraSessionController.showError("Microphone permission is needed to record.")
                }
            }

            IraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Ink,
                ) {
                    IraHome(
                        state = state,
                        serverUrl = serverUrl,
                        onServerUrlChange = { serverUrl = it },
                        onSaveServer = {
                            val normalized = serverUrl.trim()
                            preferences.edit().putString("server_url", normalized).apply()
                            IraSessionController.restoreServerUrl(normalized)
                        },
                        onRecordClick = {
                            if (state.isRecording) {
                                lifecycleScope.launch { IraSessionController.stop() }
                            } else if (ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                lifecycleScope.launch {
                                    IraSessionController.start(this@MainActivity, serverUrl)
                                }
                            } else {
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    )
                }
            }
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
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    onSaveServer: () -> Unit,
    onRecordClick: () -> Unit,
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
            text = "Private voice transcription on your local network.",
            color = Muted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(28.dp))
        ListeningCard(state = state, onRecordClick = onRecordClick)
        Spacer(Modifier.height(18.dp))
        TranscriptCard(state = state)
        Spacer(Modifier.height(18.dp))
        ServerCard(
            serverUrl = serverUrl,
            onServerUrlChange = onServerUrlChange,
            onSaveServer = onSaveServer,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "PHONE APP · WAKE WORD COMES NEXT",
            color = Muted.copy(alpha = 0.7f),
            fontSize = 10.sp,
            letterSpacing = 1.4.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(16.dp))
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
private fun ListeningCard(state: IraSessionState, onRecordClick: () -> Unit) {
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
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        tint = if (state.isRecording) Mint else Muted,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        if (state.isConnecting) " CONNECTING"
                        else if (state.isRecording) " STREAMING"
                        else if (state.isWaitingForTranscript) " TRANSCRIBING"
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
                    state.isConnecting -> "Connecting to Ira"
                    state.isWaitingForTranscript -> "Finding your words"
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
                    state.isWaitingForTranscript -> "Your audio stays on your local network."
                    else -> "Tap below and say what’s on your mind."
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
                enabled = !state.isConnecting && !state.isWaitingForTranscript,
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

@Composable
private fun ServerCard(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    onSaveServer: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171822)),
        shape = RoundedCornerShape(23.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(19.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = Lilac,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    "  YOUR LOCAL SERVER",
                    color = Muted,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editing = !editing }) {
                    Text(if (editing) "DONE" else "EDIT", color = Mint, fontSize = 10.sp)
                }
            }
            if (editing) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = onServerUrlChange,
                    label = { Text("WebSocket URL") },
                    placeholder = { Text("ws://YOUR_SERVER_HOST:8765") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        onSaveServer()
                        editing = false
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        onSaveServer()
                        editing = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF302D43)),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Save server")
                }
            } else {
                Text(
                    text = serverUrl.ifBlank { "Add your server address to get started" },
                    color = if (serverUrl.isBlank()) Muted else Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Text(
                    "Same private Wi-Fi · audio is sent to this device",
                    color = Muted.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}
