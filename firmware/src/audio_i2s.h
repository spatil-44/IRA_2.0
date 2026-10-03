#pragma once

#include <Arduino.h>
#include "config.h"

struct AudioFrame {
  int16_t samples[AUDIO_FRAME_SAMPLES];
};

bool audio_i2s_begin();
bool audio_i2s_read_frame(AudioFrame& frame);
