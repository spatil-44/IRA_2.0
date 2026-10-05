package com.neurovox.ira

import android.content.Context
import android.os.StatFs
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class OfflineModelState(
    val isInstalled: Boolean = false,
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val errorMessage: String? = null,
)

object OfflineAssistant {
    private const val MODEL_FILE_NAME = "ira-qwen3-1.7b-int4.litertlm"
    private const val MODEL_MARKER_NAME = "$MODEL_FILE_NAME.sha256"
    private const val MODEL_SIZE_BYTES = 977_184_032L
    private const val MODEL_SHA256 =
        "2eeffef7b51bc3e1225ea69fe7aa5f417397934b56a5b6c20cc068d6fd2c918b"
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/" +
            "73fbc3fe8271c162a603ee66f6e7ed25b6211195/" +
            "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"
    private const val REQUIRED_FREE_BYTES = MODEL_SIZE_BYTES + 64L * 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024

    private val downloadMutex = Mutex()
    private val _state = MutableStateFlow(OfflineModelState())
    val state = _state.asStateFlow()

    fun refreshInstalledState(context: Context) {
        _state.value = _state.value.copy(
            isInstalled = isModelInstalled(context),
            errorMessage = null,
        )
    }

    fun showDownloadError(message: String) {
        _state.value = _state.value.copy(
            isDownloading = false,
            errorMessage = message,
        )
    }

    fun isModelInstalled(context: Context): Boolean {
        val model = modelFile(context)
        val marker = markerFile(context)
        return model.isFile &&
            model.length() == MODEL_SIZE_BYTES &&
            marker.isFile &&
            runCatching { marker.readText().trim() }.getOrNull() == MODEL_SHA256
    }

    suspend fun downloadModel(context: Context) = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            val appContext = context.applicationContext
            if (isModelInstalled(appContext)) {
                refreshInstalledState(appContext)
                return@withLock
            }

            val modelDirectory = modelFile(appContext).parentFile
                ?: throw IOException("Could not find private model storage.")
            if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
                throw IOException("Could not create private model storage.")
            }
            val availableBytes = StatFs(modelDirectory.absolutePath).availableBytes
            if (availableBytes < REQUIRED_FREE_BYTES) {
                throw IOException(
                    "Free at least 1 GB of phone storage before downloading the offline model.",
                )
            }

            val temporaryFile = File(modelDirectory, "$MODEL_FILE_NAME.part")
            _state.value = _state.value.copy(
                isDownloading = true,
                progress = 0f,
                errorMessage = null,
            )
            var connection: HttpURLConnection? = null
            try {
            connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Ira-Android/0.1")
                instanceFollowRedirects = true
            }
                val statusCode = connection.responseCode
                if (statusCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("Model download failed with HTTP $statusCode.")
                }
                val contentLength = connection.contentLengthLong
                if (contentLength >= 0 && contentLength != MODEL_SIZE_BYTES) {
                    throw IOException("The model download has an unexpected file size.")
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var downloadedBytes = 0L
                connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                    FileOutputStream(temporaryFile).buffered(BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            currentCoroutineContext().ensureActive()
                            downloadedBytes += count
                            if (downloadedBytes > MODEL_SIZE_BYTES) {
                                throw IOException("The model download exceeded the expected size.")
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            _state.value = _state.value.copy(
                                progress = downloadedBytes.toFloat() / MODEL_SIZE_BYTES,
                            )
                        }
                    }
                }
                if (downloadedBytes != MODEL_SIZE_BYTES) {
                    throw IOException("The model download was incomplete.")
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (actualHash != MODEL_SHA256) {
                    throw IOException("The model failed its integrity check and was discarded.")
                }

                if (!temporaryFile.renameTo(modelFile(appContext))) {
                    throw IOException("Could not save the model in private phone storage.")
                }
                markerFile(appContext).writeText(MODEL_SHA256)
                _state.value = OfflineModelState(isInstalled = true, progress = 1f)
            } catch (cancelled: CancellationException) {
                temporaryFile.delete()
                throw cancelled
            } catch (error: Exception) {
                temporaryFile.delete()
                val failure = if (error is IOException) {
                    error
                } else {
                    IOException(
                        error.message ?: "Could not download the offline AI model.",
                        error,
                    )
                }
                _state.value = _state.value.copy(errorMessage = failure.message)
                throw failure
            } finally {
                connection?.disconnect()
                _state.value = _state.value.copy(isDownloading = false)
            }
        }
    }

    suspend fun generateReply(context: Context, prompt: String): String =
        withContext(Dispatchers.IO) {
            val model = modelFile(context.applicationContext)
            check(isModelInstalled(context)) {
                "Download the verified offline AI model before asking Ira a question."
            }
            require(prompt.isNotBlank()) { "Ira needs a recognized request to answer." }

            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
            Engine(
                EngineConfig(
                    modelPath = model.absolutePath,
                    backend = Backend.GPU(),
                    maxNumTokens = 2048,
                    cacheDir = File(context.cacheDir, "litertlm").absolutePath,
                ),
            ).use { engine ->
                engine.initialize()
                engine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(
                            "You are Ira, a concise and helpful voice assistant. " +
                                "Answer the user's request directly. " +
                                "Do not claim to have changed device settings or opened apps.",
                        ),
                        maxOutputToken = 192,
                    ),
                ).use { conversation ->
                    val reply = StringBuilder()
                    conversation.sendMessageAsync(prompt).collect { message ->
                        reply.append(message.toString())
                    }
                    reply.toString().trim().ifBlank {
                        throw IOException("The offline model returned an empty reply.")
                    }
                }
            }
        }

    private fun modelFile(context: Context): File =
        File(File(context.filesDir, "offline-models"), MODEL_FILE_NAME)

    private fun markerFile(context: Context): File =
        File(File(context.filesDir, "offline-models"), MODEL_MARKER_NAME)
}
