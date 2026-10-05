package com.neurovox.ira

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONException
import org.json.JSONObject

object IraServerSettings {
    private const val PREFERENCES = "ira_server"
    private const val ENDPOINT_KEY = "endpoint"
    const val DEFAULT_ENDPOINT = "ws://192.168.1.50:8765/"

    fun endpoint(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ENDPOINT_KEY, DEFAULT_ENDPOINT)
            ?: DEFAULT_ENDPOINT

    fun saveEndpoint(context: Context, endpoint: String) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ENDPOINT_KEY, normalizeEndpoint(endpoint))
            .apply()
    }

    fun normalizeEndpoint(value: String): String {
        val endpoint = value.trim()
        val uri = try {
            URI(endpoint)
        } catch (error: URISyntaxException) {
            throw IllegalArgumentException(
                "Enter a valid WebSocket address, such as ws://192.168.1.50:8765/.",
                error,
            )
        }
        require(uri.scheme == "ws" || uri.scheme == "wss") {
            "The server address must start with ws:// or wss://."
        }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "Enter a server hostname or IP address without a username or password."
        }
        require(uri.port == -1 || uri.port in 1..65535) {
            "The server port must be between 1 and 65535."
        }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "The server address cannot include a query or fragment."
        }
        return endpoint
    }
}

data class IraServerResult(
    val transcript: String,
    val assistantReply: String?,
    val assistantError: String?,
    val latencyMs: Double?,
)

object IraServerVoiceSession {
    private const val SAMPLE_RATE = 16_000
    private const val MAX_AUDIO_SECONDS = 30
    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val RESPONSE_TIMEOUT_MS = 360_000L
    private const val READ_BUFFER_BYTES = 6_400

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun recordAndTranscribe(
        context: Context,
        endpoint: String,
        shouldStop: () -> Boolean,
        onRecordingStarted: () -> Unit,
        onWaitingForReply: () -> Unit,
    ): IraServerResult {
        val opened = CompletableDeferred<Unit>()
        val result = CompletableDeferred<IraServerResult>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    when (message.optString("type")) {
                        "ready" -> Unit
                        "error" -> result.completeExceptionally(
                            IOException(
                                message.optString("message", "The Pi rejected the request."),
                            ),
                        )
                        "transcript" -> {
                            val transcript = message.optString("text").trim()
                            if (transcript.isBlank()) {
                                result.completeExceptionally(
                                    IOException("The Pi could not recognize any speech."),
                                )
                            } else {
                                result.complete(
                                    IraServerResult(
                                        transcript = transcript,
                                        assistantReply = message.optString("assistant_reply")
                                            .takeIf(String::isNotBlank),
                                        assistantError = message.optString("assistant_error")
                                            .takeIf(String::isNotBlank),
                                        latencyMs = message
                                            .takeIf { it.has("latency_ms") }
                                            ?.optDouble("latency_ms")
                                            ?.takeIf(Double::isFinite),
                                    ),
                                )
                            }
                        }
                        else -> result.completeExceptionally(
                            IOException("The Pi sent an unexpected WebSocket response."),
                        )
                    }
                } catch (error: JSONException) {
                    result.completeExceptionally(
                        IOException("The Pi sent an invalid response.", error),
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                val failure = IOException(
                    response?.message?.takeIf(String::isNotBlank)
                        ?: error.message
                        ?: "Could not connect to the Pi.",
                    error,
                )
                opened.completeExceptionally(failure)
                result.completeExceptionally(failure)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!result.isCompleted) {
                    result.completeExceptionally(
                        IOException(reason.ifBlank { "The Pi closed the connection." }),
                    )
                }
            }
        }

        var recorder: AudioRecord? = null
        var webSocket: WebSocket? = null
        try {
            val request = Request.Builder()
                .url(IraServerSettings.normalizeEndpoint(endpoint))
                .build()
            val socket = client.newWebSocket(request, listener)
            webSocket = socket
            withTimeout(CONNECT_TIMEOUT_MS) { opened.await() }
            if (shouldStop()) throw IOException("Recording was stopped before it started.")
            check(
                socket.send(
                    """{"type":"start","sample_rate":16000,"codec":"pcm_s16le"}""",
                ),
            ) {
                "Could not start the audio stream to the Pi."
            }

            val minBufferBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferBytes <= 0) {
                throw IOException("This phone does not support 16 kHz microphone recording.")
            }
            val audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBufferBytes, READ_BUFFER_BYTES * 2))
                .build()
            recorder = audioRecord
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                throw IOException("Android could not initialize the microphone.")
            }
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IOException("Android could not start microphone recording.")
            }
            onRecordingStarted()

            val buffer = ByteArray(READ_BUFFER_BYTES)
            var sentSamples = 0
            val maxSamples = SAMPLE_RATE * MAX_AUDIO_SECONDS
            while (!shouldStop() && sentSamples < maxSamples) {
                val bytesRead = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (bytesRead < 0) {
                    throw IOException("Android microphone recording failed (code $bytesRead).")
                }
                val bytesToSend = minOf(bytesRead, (maxSamples - sentSamples) * 2)
                if (bytesToSend > 0) {
                    check(socket.send(ByteString.of(buffer, 0, bytesToSend))) {
                        "The connection to the Pi was lost while sending audio."
                    }
                    sentSamples += bytesToSend / 2
                }
            }
            if (sentSamples == 0) throw IOException("No microphone audio was captured.")

            audioRecord.stop()
            onWaitingForReply()
            check(socket.send("""{"type":"end"}""")) {
                "Could not finish sending audio to the Pi."
            }
            return withTimeout(RESPONSE_TIMEOUT_MS) { result.await() }
        } finally {
            recorder?.let { audioRecord ->
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
                audioRecord.release()
            }
            webSocket?.close(1000, "voice session complete")
        }
    }
}
