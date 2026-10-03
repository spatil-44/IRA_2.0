#include "audio_i2s.h"

#include <driver/i2s.h>

namespace {
constexpr i2s_port_t I2S_PORT = I2S_NUM_0;
int32_t rawSamples[AUDIO_FRAME_SAMPLES];
}

bool audio_i2s_begin() {
  const i2s_config_t config = {
      .mode = static_cast<i2s_mode_t>(I2S_MODE_MASTER | I2S_MODE_RX),
      .sample_rate = AUDIO_SAMPLE_RATE,
      .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,
      .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
      .communication_format = I2S_COMM_FORMAT_STAND_I2S,
      .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
      .dma_buf_count = 4,
      .dma_buf_len = AUDIO_FRAME_SAMPLES,
      .use_apll = false,
      .tx_desc_auto_clear = false,
      .fixed_mclk = 0,
  };

  const i2s_pin_config_t pins = {
      .bck_io_num = PIN_I2S_SCK,
      .ws_io_num = PIN_I2S_WS,
      .data_out_num = I2S_PIN_NO_CHANGE,
      .data_in_num = PIN_I2S_SD,
  };

  if (i2s_driver_install(I2S_PORT, &config, 0, nullptr) != ESP_OK) {
    return false;
  }
  if (i2s_set_pin(I2S_PORT, &pins) != ESP_OK) {
    i2s_driver_uninstall(I2S_PORT);
    return false;
  }
  return i2s_zero_dma_buffer(I2S_PORT) == ESP_OK;
}

bool audio_i2s_read_frame(AudioFrame& frame) {
  size_t bytesRead = 0;
  const size_t expectedBytes = sizeof(rawSamples);
  size_t totalBytes = 0;

  while (totalBytes < expectedBytes) {
    if (i2s_read(I2S_PORT,
                 reinterpret_cast<uint8_t*>(rawSamples) + totalBytes,
                 expectedBytes - totalBytes, &bytesRead, portMAX_DELAY) !=
        ESP_OK) {
      return false;
    }
    totalBytes += bytesRead;
  }

  for (size_t i = 0; i < AUDIO_FRAME_SAMPLES; ++i) {
    const int32_t sample = rawSamples[i] >> 16;
    frame.samples[i] = static_cast<int16_t>(
        sample > INT16_MAX ? INT16_MAX : sample < INT16_MIN ? INT16_MIN : sample);
  }
  return true;
}
