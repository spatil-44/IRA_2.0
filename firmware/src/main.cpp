#include <Arduino.h>
#include <WiFi.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/task.h>

#include <atomic>
#include <algorithm>
#include <cstring>

#include "audio_i2s.h"
#include "config.h"
#include "kws.h"
#include "stream_ws.h"

namespace {
constexpr UBaseType_t STREAM_QUEUE_LENGTH = 40;
QueueHandle_t kwsQueue = nullptr;
QueueHandle_t streamQueue = nullptr;
TaskHandle_t streamTaskHandle = nullptr;
std::atomic<bool> kwsReady{false};
int16_t rollingWindow[KWS_WINDOW_SAMPLES] = {};

bool credentials_configured() {
  return strcmp(IRA_WIFI_SSID, "YOUR_WIFI_SSID") != 0 &&
         strcmp(IRA_WIFI_PASSWORD, "YOUR_WIFI_PASSWORD") != 0;
}

void audio_capture_task(void*) {
  AudioFrame frame;
  for (;;) {
    if (!audio_i2s_read_frame(frame)) {
      Serial.println("I2S read failed.");
      vTaskDelay(pdMS_TO_TICKS(20));
      continue;
    }
    if (xQueueSend(kwsQueue, &frame, 0) != pdTRUE) {
      Serial.println("KWS audio queue full; frame dropped.");
    }
    if (stream_ws_wants_audio() &&
        xQueueSend(streamQueue, &frame, 0) != pdTRUE) {
      Serial.println("Stream audio queue full; frame dropped.");
    }
  }
}

void inference_task(void*) {
  AudioFrame frame;
  size_t writeIndex = 0;
  uint32_t framesSinceInference = 0;
  uint32_t lastWakeAt = 0;

  kwsReady.store(kws_begin());
  if (!kwsReady.load()) {
    Serial.println("KWS model unavailable: train and export model_data.h.");
  }

  for (;;) {
    if (xQueueReceive(kwsQueue, &frame, portMAX_DELAY) != pdTRUE) {
      continue;
    }
    for (size_t i = 0; i < AUDIO_FRAME_SAMPLES; ++i) {
      rollingWindow[writeIndex] = frame.samples[i];
      writeIndex = (writeIndex + 1) % KWS_WINDOW_SAMPLES;
    }
    if (!kwsReady.load() || ++framesSinceInference < 5) {
      continue;
    }
    framesSinceInference = 0;

    std::rotate(rollingWindow, rollingWindow + writeIndex,
                rollingWindow + KWS_WINDOW_SAMPLES);
    writeIndex = 0;

    float iraScore = 0.0f;
    if (kws_detect(rollingWindow, iraScore)) {
      const uint32_t now = millis();
      if (now - lastWakeAt > 1500 && !stream_ws_is_active()) {
        lastWakeAt = now;
        Serial.printf("Wake word detected (score %.2f).\n", iraScore);
        xQueueReset(streamQueue);
        xTaskNotifyGive(streamTaskHandle);
      }
    }
  }
}
}

void setup() {
  Serial.begin(115200);
  delay(200);

  if (!audio_i2s_begin()) {
    Serial.println("Failed to initialize INMP441 I2S capture.");
    while (true) {
      delay(1000);
    }
  }

  kwsQueue = xQueueCreate(4, sizeof(AudioFrame));
  streamQueue = xQueueCreate(STREAM_QUEUE_LENGTH, sizeof(AudioFrame));
  if (kwsQueue == nullptr || streamQueue == nullptr) {
    Serial.println("Failed to allocate audio queues.");
    while (true) {
      delay(1000);
    }
  }

  if (credentials_configured()) {
    WiFi.mode(WIFI_STA);
    WiFi.begin(IRA_WIFI_SSID, IRA_WIFI_PASSWORD);
    Serial.println("Connecting to configured WiFi.");
  } else {
    Serial.println("WiFi not configured; edit firmware/include/secrets.h.");
  }

  const BaseType_t audioTaskCreated =
      xTaskCreatePinnedToCore(audio_capture_task, "audio_capture", 4096, nullptr,
                              4, nullptr, 0);
  const BaseType_t inferenceTaskCreated =
      xTaskCreatePinnedToCore(inference_task, "kws_inference", 8192, nullptr, 3,
                              nullptr, 1);
  const BaseType_t streamTaskCreated = xTaskCreatePinnedToCore(
      [](void*) { stream_ws_run(streamQueue); }, "audio_stream", 8192, nullptr,
      2, &streamTaskHandle, 1);
  if (audioTaskCreated != pdPASS || inferenceTaskCreated != pdPASS ||
      streamTaskCreated != pdPASS) {
    Serial.println("Failed to start a FreeRTOS task.");
    while (true) {
      delay(1000);
    }
  }
}

void loop() {
  delay(1000);
}
