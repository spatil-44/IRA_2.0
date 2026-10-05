package com.neurovox.ira

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WakeDatasetCollector {
    private const val SAMPLE_RATE = 16_000
    private const val SAMPLE_COUNT = SAMPLE_RATE

    suspend fun recordSample(context: android.content.Context, label: String): String =
        withContext(Dispatchers.IO) {
            require(label in setOf("ira", "unknown", "noise")) {
                "Unsupported training sample label."
            }
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Microphone permission is required to collect samples.")
            }

            val minimumBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimumBufferSize <= 0) {
                throw IllegalStateException("This phone could not initialize 16 kHz audio.")
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
                .setBufferSizeInBytes(maxOf(minimumBufferSize, SAMPLE_COUNT * 2))
                .build()
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                throw IllegalStateException("Microphone initialization failed.")
            }

            val samples = ShortArray(SAMPLE_COUNT)
            try {
                audioRecord.startRecording()
                var offset = 0
                while (offset < samples.size) {
                    val count = audioRecord.read(
                        samples,
                        offset,
                        samples.size - offset,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (count < 0) {
                        throw IOException("Microphone read failed ($count).")
                    }
                    if (count == 0) continue
                    offset += count
                }
            } finally {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
                audioRecord.release()
            }

            val documents = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: File(context.filesDir, Environment.DIRECTORY_DOCUMENTS)
            val destination = File(File(documents, "IraDataset"), label)
            if (!destination.exists() && !destination.mkdirs()) {
                throw IOException("Could not create the training-data folder.")
            }

            val output = File(
                destination,
                "${label}_${System.currentTimeMillis()}_${System.nanoTime() % 1_000_000}.wav",
            )
            writeWaveFile(output, samples)
            output.absolutePath
        }

    private fun writeWaveFile(file: File, samples: ShortArray) {
        val dataSize = samples.size * Short.SIZE_BYTES
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(StandardCharsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(StandardCharsets.US_ASCII))
        header.put("fmt ".toByteArray(StandardCharsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * Short.SIZE_BYTES)
        header.putShort(Short.SIZE_BYTES.toShort())
        header.putShort(16)
        header.put("data".toByteArray(StandardCharsets.US_ASCII))
        header.putInt(dataSize)

        val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            pcm.putShort(sample)
        }
        FileOutputStream(file).use { output ->
            output.write(header.array())
            output.write(pcm.array())
        }
    }
}
