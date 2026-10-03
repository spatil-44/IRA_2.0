#include "stream_ws.h"

#include <Arduino.h>
#include <WiFi.h>
#include <WebSocketsClient.h>
#include <opus.h>

#include <atomic>
#include <cmath>
#include <cstring>

#include "audio_i2s.h"
#include "config.h"

namespace {
std::atomic<bool> streaming{false};
std::atomic<bool> captureAudio{false};
bool socketConnected = false;
bool responseReceived = false;

void on_websocket_event(WStype_t type, uint8_t* payload, size_t length) {
  if (type == WStype_CONNECTED) {
    socketConnected = true;
  } else if (type == WStype_DISCONNECTED) {
    socketConnected = false;
  } else if (type == WStype_TEXT) {
    Serial.write(payload, length);
    Serial.println();
    responseReceived = true;
  }
}

bool credentials_configured() {
  return strcmp(IRA_WIFI_SSID, "YOUR_WIFI_SSID") != 0 &&
         strcmp(IRA_SERVER_HOST, "YOUR_SERVER_HOST") != 0;
}
}

bool stream_ws_is_active() {
  return streaming.load();
}

bool stream_ws_wants_audio() {
  return captureAudio.load();
}

void stream_ws_run(QueueHandle_t audioQueue) {
  WebSocketsClient socket;
  OpusEncoder* encoder = nullptr;
  uint8_t encoded[400];
  AudioFrame frame;

  for (;;) {
    ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
    if (!credentials_configured() || WiFi.status() != WL_CONNECTED) {
      Serial.println("Streaming skipped: configure WiFi/server in secrets.h.");
      streaming.store(false);
      captureAudio.store(false);
      continue;
    }

    streaming.store(true);
    captureAudio.store(true);
    socketConnected = false;
    responseReceived = false;
    socket.onEvent(on_websocket_event);
    socket.begin(IRA_SERVER_HOST, IRA_SERVER_PORT, IRA_SERVER_PATH);
    socket.setReconnectInterval(0);

    const uint32_t connectStarted = millis();
    while (!socketConnected && millis() - connectStarted < 5000) {
      socket.loop();
      vTaskDelay(pdMS_TO_TICKS(10));
    }
    if (!socketConnected) {
      Serial.println("WebSocket connection failed.");
      socket.disconnect();
      streaming.store(false);
      captureAudio.store(false);
      continue;
    }

    int opusError = OPUS_OK;
    encoder = opus_encoder_create(AUDIO_SAMPLE_RATE, 1, OPUS_APPLICATION_VOIP,
                                  &opusError);
    if (encoder == nullptr || opusError != OPUS_OK) {
      Serial.println("Opus encoder initialization failed.");
      if (encoder != nullptr) {
        opus_encoder_destroy(encoder);
        encoder = nullptr;
      }
      socket.disconnect();
      streaming.store(false);
      captureAudio.store(false);
      continue;
    }
    if (opus_encoder_ctl(encoder, OPUS_SET_BITRATE(16000)) != OPUS_OK ||
        opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(2)) != OPUS_OK) {
      Serial.println("Opus encoder configuration failed.");
      opus_encoder_destroy(encoder);
      encoder = nullptr;
      socket.disconnect();
      streaming.store(false);
      captureAudio.store(false);
      continue;
    }
    if (!socket.sendTXT("{\"type\":\"start\",\"sample_rate\":16000}")) {
      Serial.println("Could not send WebSocket start message.");
      opus_encoder_destroy(encoder);
      encoder = nullptr;
      socket.disconnect();
      streaming.store(false);
      captureAudio.store(false);
      continue;
    }

    const uint32_t streamStarted = millis();
    uint32_t firstVoiceAt = 0;
    uint32_t lastVoiceAt = streamStarted;
    bool hadVoice = false;

    while (socketConnected && millis() - streamStarted < 10000) {
      socket.loop();
      if (xQueueReceive(audioQueue, &frame, pdMS_TO_TICKS(100)) != pdTRUE) {
        continue;
      }

      uint64_t energy = 0;
      for (int16_t sample : frame.samples) {
        energy += static_cast<int32_t>(sample) * sample;
      }
      const float rms = sqrtf(static_cast<float>(energy) / AUDIO_FRAME_SAMPLES);
      const uint32_t now = millis();
      if (rms > 450.0f) {
        if (!hadVoice) {
          firstVoiceAt = now;
        }
        hadVoice = true;
        lastVoiceAt = now;
      }

      const int packetLength =
          opus_encode(encoder, frame.samples, AUDIO_FRAME_SAMPLES, encoded,
                      sizeof(encoded));
      if (packetLength < 0) {
        Serial.printf("Opus encode failed: %d\n", packetLength);
        break;
      }
      if (!socket.sendBIN(encoded, static_cast<size_t>(packetLength))) {
        Serial.println("Could not send Opus WebSocket packet.");
        break;
      }
      if (hadVoice && now - lastVoiceAt > 1000 && now - firstVoiceAt > 300) {
        break;
      }
    }

    if (socketConnected) {
      if (!socket.sendTXT("{\"type\":\"end\"}")) {
        Serial.println("Could not send WebSocket end message.");
      }
    }
    captureAudio.store(false);
    opus_encoder_destroy(encoder);
    encoder = nullptr;

    const uint32_t responseStarted = millis();
    while (socketConnected && !responseReceived &&
           millis() - responseStarted < 30000) {
      socket.loop();
      vTaskDelay(pdMS_TO_TICKS(10));
    }
    if (!responseReceived) {
      Serial.println("No transcript response received before timeout.");
    }
    socket.disconnect();
    streaming.store(false);
    captureAudio.store(false);
  }
}
