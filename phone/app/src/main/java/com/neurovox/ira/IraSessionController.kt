package com.neurovox.ira

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IraSessionState(
    val isConnecting: Boolean = false,
    val isRecording: Boolean = false,
    val isWaitingForTranscript: Boolean = false,
    val transcript: String? = null,
    val assistantReply: String? = null,
    val assistantError: String? = null,
    val latencyMs: Double? = null,
    val errorMessage: String? = null,
)

object IraSessionController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(IraSessionState())
    val state: StateFlow<IraSessionState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private var stopRequested = AtomicBoolean(false)
    private var sessionActive = false

    fun showError(message: String) {
        _state.value = _state.value.copy(errorMessage = message)
    }

    fun start(context: Context) {
        start(context, IraServerSettings.endpoint(context))
    }

    fun start(context: Context, endpoint: String) {
        if (sessionActive) return
        try {
            IraServerSettings.normalizeEndpoint(endpoint)
        } catch (error: IllegalArgumentException) {
            showError(error.message ?: "Enter a valid Pi WebSocket address.")
            return
        }

        sessionActive = true
        stopRequested = AtomicBoolean(false)
        _state.value = IraSessionState(isConnecting = true)
        sessionJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    IraServerVoiceSession.recordAndTranscribe(
                        context = context.applicationContext,
                        endpoint = endpoint,
                        shouldStop = stopRequested::get,
                        onRecordingStarted = {
                            _state.value = _state.value.copy(
                                isConnecting = false,
                                isRecording = true,
                            )
                        },
                        onWaitingForReply = {
                            _state.value = _state.value.copy(
                                isRecording = false,
                                isWaitingForTranscript = true,
                            )
                        },
                    )
                }
                _state.value = _state.value.copy(
                    isConnecting = false,
                    isRecording = false,
                    isWaitingForTranscript = false,
                    transcript = result.transcript,
                    assistantReply = result.assistantReply,
                    assistantError = result.assistantError,
                    latencyMs = result.latencyMs,
                    errorMessage = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    isConnecting = false,
                    isRecording = false,
                    isWaitingForTranscript = false,
                    errorMessage = error.message
                        ?: "The Pi voice session failed. Check the server address and Wi-Fi.",
                )
            } finally {
                sessionActive = false
                sessionJob = null
            }
        }
    }

    fun stop() {
        if (!sessionActive || !_state.value.isRecording) return
        stopRequested.set(true)
        _state.value = _state.value.copy(
            isRecording = false,
            isWaitingForTranscript = true,
            errorMessage = null,
        )
    }

    fun stopImmediately() {
        stopRequested.set(true)
        sessionActive = false
        sessionJob?.cancel()
        sessionJob = null
        _state.value = _state.value.copy(
            isConnecting = false,
            isRecording = false,
            isWaitingForTranscript = false,
        )
    }
}
