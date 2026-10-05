package com.neurovox.ira

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.Locale

class IraWakeWordService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var wakeWordSeen = false
    private var awaitingCommand = false

    private val restartListening = Runnable {
        if (running) startRecognition()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening("Wake-word listening stopped.")
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        startForegroundWithNotification()
        if (!hasRequiredPermissions()) {
            stopListening("Allow microphone and notification access before listening.")
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            stopListening("This phone does not provide on-device speech recognition. " +
                "Ira did not start listening.")
            return START_NOT_STICKY
        }

        try {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
                setRecognitionListener(recognitionListener)
            }
        } catch (error: IllegalArgumentException) {
            stopListening("Could not create the on-device recognizer: " +
                (error.message ?: "unsupported configuration"))
            return START_NOT_STICKY
        } catch (error: SecurityException) {
            stopListening("Android denied access to its on-device speech recognizer.")
        } catch (error: UnsupportedOperationException) {
            stopListening("Android's on-device speech recognizer is unavailable: " +
                (error.message ?: "unsupported on this phone"))
            return START_NOT_STICKY
        }

        running = true
        wakeWordSeen = false
        awaitingCommand = false
        IraVoiceControlStatus.update(true, "Listening on this phone for “Ira”.")
        startRecognition()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        if (IraVoiceControlStatus.state.value.isListening) {
            IraVoiceControlStatus.update(false, "Wake-word listening stopped.")
        }
        running = false
        super.onDestroy()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            if (wakeWordSeen || awaitingCommand) {
                IraAccessibilityService.setAssistantAudioLevel((rmsdB + 2f) / 12f)
            }
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = recognitionText(partialResults)
            if (!wakeWordSeen && DeviceVoiceCommandParser.hasWakeWord(partial)) {
                wakeWordSeen = true
                IraVoiceControlStatus.updateMessage(
                    "Ira heard. Listening locally for your command.",
                )
                IraAccessibilityService.showAssistantOverlay(
                    "IRA",
                    "Say a command",
                    listening = true,
                )
            }
        }

        override fun onResults(results: Bundle?) {
            handleRecognitionResult(recognitionText(results))
        }

        override fun onError(error: Int) {
            if (!running) return
            val assistantWasVisible = wakeWordSeen || awaitingCommand
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                stopListening("Android denied microphone access. Wake-word listening stopped.")
                return
            }
            if (error == SpeechRecognizer.ERROR_AUDIO) {
                stopListening("The microphone returned an audio error. Wake-word listening stopped.")
                return
            }
            val detail = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    "The on-device recognizer is busy."
                else -> "On-device speech recognition failed (code $error)."
            }
            wakeWordSeen = false
            if (awaitingCommand) {
                awaitingCommand = false
                IraVoiceControlStatus.updateMessage(
                    "$detail Listening locally for “Ira” again.",
                )
            } else {
                IraVoiceControlStatus.updateMessage(
                    "$detail Retrying only the on-device recognizer.",
                )
            }
            if (assistantWasVisible) {
                IraAccessibilityService.showAssistantOverlay(
                    "Try again",
                    "Say Ira, then a command",
                    listening = false,
                    durationMillis = 1800L,
                )
            }
            scheduleRecognitionRestart(700)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun handleRecognitionResult(text: String) {
        val wakeWasHeard = wakeWordSeen || DeviceVoiceCommandParser.hasWakeWord(text)
        wakeWordSeen = false
        if (!awaitingCommand && !wakeWasHeard) {
            scheduleRecognitionRestart(RESTART_DELAY_MS)
            return
        }

        val commandText = if (awaitingCommand) {
            text.trim()
        } else {
            DeviceVoiceCommandParser.commandAfterWakeWord(text, wakeWasHeard).orEmpty()
        }
        awaitingCommand = false

        if (commandText.isBlank()) {
            awaitingCommand = true
            IraVoiceControlStatus.updateMessage(
                "Ira heard. Say a command now; audio stays on this phone.",
            )
            IraAccessibilityService.showAssistantOverlay(
                "I’m listening",
                "Say a device command",
                listening = true,
            )
            scheduleRecognitionRestart(RESTART_DELAY_MS)
            return
        }

        val command = DeviceVoiceCommandParser.parse(commandText)
        if (command == null) {
            val message =
                "For transcription and assistant replies, use Start talking to send speech " +
                    "to your configured Pi. This private listener handles device commands."
            IraVoiceControlStatus.updateMessage(message)
            IraAccessibilityService.showAssistantOverlay(
                "Try a command",
                "Say open an app or a setting",
                listening = false,
                durationMillis = 3000L,
            )
        } else if (!IraAccessibilityService.isEnabled(this)) {
            IraVoiceControlStatus.updateMessage(
                "Enable Ira device voice control in Android Accessibility settings first. " +
                    "No command or screen data was sent to a server.",
            )
            IraAccessibilityService.showAssistantOverlay(
                "One more step",
                "Enable Ira in Accessibility settings",
                listening = false,
                durationMillis = 3200L,
            )
        } else if (!IraAccessibilityService.performVoiceCommand(command)) {
            IraVoiceControlStatus.updateMessage(
                "Android accessibility control is not connected. Re-enable Ira in " +
                    "Accessibility settings, then try again.",
            )
            IraAccessibilityService.showAssistantOverlay(
                "Reconnect Ira",
                "Re-enable Accessibility control",
                listening = false,
                durationMillis = 3200L,
            )
        }
        scheduleRecognitionRestart(RESTART_DELAY_MS)
    }

    private fun recognitionText(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()

    private fun startRecognition() {
        if (!running) return
        try {
            recognizer?.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                },
            )
        } catch (error: IllegalStateException) {
            stopListening("The on-device recognizer could not start: " +
                (error.message ?: "Android rejected the request"))
        } catch (error: SecurityException) {
            stopListening("Android denied access to on-device speech recognition.")
        }
    }

    private fun scheduleRecognitionRestart(delayMillis: Long) {
        handler.removeCallbacks(restartListening)
        handler.postDelayed(restartListening, delayMillis)
    }

    private fun hasRequiredPermissions(): Boolean {
        val microphoneGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return microphoneGranted && notificationsGranted
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, IraWakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Ira is listening for its wake word")
            .setContentText("On-device only. Tap Stop to turn off the microphone.")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(
                android.app.Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this,
                        android.R.drawable.ic_media_pause,
                    ),
                    "Stop",
                    stopIntent,
                ).build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ira on-device wake-word listening",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Visible control for local “Ira” wake-word listening."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun stopListening(message: String) {
        running = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        IraAccessibilityService.hideAssistantOverlay()
        IraVoiceControlStatus.update(false, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.neurovox.ira.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.neurovox.ira.action.STOP_WAKE_WORD"

        private const val CHANNEL_ID = "ira_local_wake_word"
        private const val NOTIFICATION_ID = 81
        private const val STOP_REQUEST_CODE = 82
        private const val RESTART_DELAY_MS = 350L
    }
}
