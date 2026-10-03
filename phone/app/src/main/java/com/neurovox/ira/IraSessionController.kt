package com.neurovox.ira

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Process
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

data class IraSessionState(
    val isConnecting: Boolean = false,
    val isRecording: Boolean = false,
    val isWaitingForTranscript: Boolean = false,
    val transcript: String? = null,
    val latencyMs: Double? = null,
    val errorMessage: String? = null,
)

object IraSessionController {
    private const val SAMPLE_RATE = 16_000
    private const val FRAME_SAMPLES = 320
    private const val MAX_RECORDING_SECONDS = 30

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionMutex = Mutex()
    private val _state = MutableStateFlow(IraSessionState())
    val state: StateFlow<IraSessionState> = _state.asStateFlow()

    @Volatile
    private var savedUrl = ""
    @Volatile
    private var socket: WebSocket? = null
    @Volatile
    private var recorder: AudioRecord? = null
    @Volatile
    private var captureJob: Job? = null
    @Volatile
    private var responseTimeoutJob: Job? = null
    @Volatile
    private var sessionActive = false
    @Volatile
    private var userStopped = false

    fun restoreServerUrl(url: String) {
        savedUrl = url
    }

    fun savedServerUrl(): String = savedUrl

    fun showError(message: String) {
        _state.value = _state.value.copy(errorMessage = message)
    }

    suspend fun start(context: Context, url: String) = sessionMutex.withLock {
        if (sessionActive) return@withLock
        val serverUri = validateServerUrl(url)
        if (serverUri == null) {
            _state.value = _state.value.copy(
                errorMessage = "Enter a valid ws:// or wss:// server URL first.",
            )
            return@withLock
        }

        savedUrl = url.trim()
        userStopped = false
        sessionActive = true
        _state.value = _state.value.copy(
            isConnecting = true,
            isRecording = false,
            isWaitingForTranscript = false,
            errorMessage = null,
        )

        try {
            val request = Request.Builder().url(serverUri.toString()).build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (webSocket !== socket) return
                    val started = webSocket.send(
                        JSONObject()
                            .put("type", "start")
                            .put("sample_rate", SAMPLE_RATE)
                            .put("codec", "pcm_s16le")
                            .toString(),
                    )
                    if (!started) {
                        failSession("Could not start the audio stream.")
                        return
                    }
                    captureJob = scope.launch { beginCapture(context.applicationContext) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (webSocket !== socket) return
                    handleServerMessage(text)
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    error: Throwable,
                    response: Response?,
                ) {
                    if (webSocket !== socket) return
                    failSession(error.message ?: "Could not reach the transcription server.")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (webSocket !== socket) return
                    if (sessionActive) {
                        failSession("The server closed the audio connection.")
                    } else {
                        finishSession()
                    }
                }
            })
        } catch (error: IllegalArgumentException) {
            finishSession()
            _state.value = _state.value.copy(
                isConnecting = false,
                errorMessage = "The server URL is not valid.",
            )
        }
    }

    suspend fun stop() = sessionMutex.withLock {
        if (!sessionActive || userStopped) return@withLock
        userStopped = true
        _state.value = _state.value.copy(
            isRecording = false,
            isWaitingForTranscript = true,
            errorMessage = null,
        )
        captureJob?.cancel()
        captureJob?.join()
        releaseRecorder()
        val currentSocket = socket
        if (currentSocket == null ||
            !currentSocket.send(JSONObject().put("type", "end").toString())
        ) {
            failSession("The audio stream ended before it could be sent.")
        } else {
            responseTimeoutJob = scope.launch {
                delay(TimeUnit.SECONDS.toMillis(120))
                if (sessionActive && _state.value.isWaitingForTranscript) {
                    failSession("The server did not return a transcript in time.")
                }
            }
        }
    }

    fun stopImmediately() {
        userStopped = true
        captureJob?.cancel()
        releaseRecorder()
        socket?.cancel()
        socket = null
        sessionActive = false
    }

    private fun validateServerUrl(value: String): Uri? {
        val uri = runCatching { Uri.parse(value.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("ws", "wss") || uri.host.isNullOrBlank() ||
            uri.userInfo != null || uri.query != null || uri.fragment != null
        ) {
            return null
        }
        val port = uri.port
        if (port != -1 && port !in 1..65535) return null
        return uri
    }

    private suspend fun beginCapture(context: Context) {
        withContext(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                val minimumBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minimumBuffer <= 0) {
                    failSession("This device could not initialize 16 kHz microphone capture.")
                    return@withContext
                }
                val audioRecord = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minimumBuffer, FRAME_SAMPLES * 2 * 4))
                    .build()
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord.release()
                    failSession("Microphone initialization failed.")
                    return@withContext
                }
                recorder = audioRecord
                audioRecord.startRecording()
                _state.value = _state.value.copy(
                    isConnecting = false,
                    isRecording = true,
                    isWaitingForTranscript = false,
                    errorMessage = null,
                )

                val frame = ShortArray(FRAME_SAMPLES)
                val frameBuffer = ByteBuffer.allocate(FRAME_SAMPLES * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                val beganAt = System.nanoTime()
                while (sessionActive && !userStopped) {
                    if (System.nanoTime() - beganAt >=
                        TimeUnit.SECONDS.toNanos(MAX_RECORDING_SECONDS.toLong())
                    ) {
                        scope.launch { stop() }
                        break
                    }
                    val count = audioRecord.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                    if (count < 0) {
                        failSession("Microphone read failed ($count).")
                        return@withContext
                    }
                    if (count == 0) continue

                    frameBuffer.clear()
                    for (index in 0 until count) {
                        frameBuffer.putShort(frame[index])
                    }
                    val packet = frameBuffer.array().copyOf(count * 2)
                    if (client.dispatcher.executorService.isShutdown ||
                        (socket?.queueSize() ?: Long.MAX_VALUE) > 512_000L ||
                        socket?.send(packet) != true
                    ) {
                        failSession("Audio could not be sent to the server.")
                        return@withContext
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                failSession("Microphone permission is required.")
            } catch (error: IllegalStateException) {
                failSession(error.message ?: "Microphone capture stopped unexpectedly.")
            } finally {
                releaseRecorder()
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            }
        }
    }

    private fun handleServerMessage(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull()
        if (message == null) {
            failSession("The server sent an invalid response.")
            return
        }
        when (message.optString("type")) {
            "ready" -> Unit
            "error" -> failSession(message.optString("message", "Server rejected the audio."))
            "transcript" -> {
                _state.value = _state.value.copy(
                    isConnecting = false,
                    isRecording = false,
                    isWaitingForTranscript = false,
                    transcript = message.optString("text"),
                    latencyMs = message.optDouble("latency_ms").takeIf { it.isFinite() },
                    errorMessage = null,
                )
                finishSession(closeSocket = true)
            }
            else -> failSession("The server sent an unknown response.")
        }
    }

    private fun failSession(message: String) {
        userStopped = true
        captureJob?.cancel()
        releaseRecorder()
        socket?.close(1000, "session ended")
        finishSession(closeSocket = true)
        _state.value = _state.value.copy(
            isConnecting = false,
            isRecording = false,
            isWaitingForTranscript = false,
            errorMessage = message,
        )
    }

    private fun finishSession(closeSocket: Boolean = false) {
        sessionActive = false
        captureJob = null
        responseTimeoutJob?.cancel()
        responseTimeoutJob = null
        releaseRecorder()
        if (closeSocket) {
            socket?.close(1000, "session ended")
        }
        socket = null
    }

    @Synchronized
    private fun releaseRecorder() {
        recorder?.let { audioRecord ->
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { audioRecord.stop() }
            }
            audioRecord.release()
        }
        recorder = null
    }
}
