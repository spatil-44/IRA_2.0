#pragma once

#include <stdint.h>

#if __has_include("secrets.h")
#include "secrets.h"
#else
#include "secrets.example.h"
#endif

constexpr uint8_t PIN_I2S_SCK = 26;
constexpr uint8_t PIN_I2S_WS = 25;
constexpr uint8_t PIN_I2S_SD = 33;
constexpr uint32_t AUDIO_SAMPLE_RATE = 16000;
constexpr uint16_t AUDIO_FRAME_SAMPLES = 320;
constexpr uint16_t KWS_WINDOW_SAMPLES = 16000;
constexpr float KWS_DETECTION_THRESHOLD = 0.85f;

#ifndef IRA_WIFI_SSID
#define IRA_WIFI_SSID "YOUR_WIFI_SSID"
#endif

#ifndef IRA_WIFI_PASSWORD
#define IRA_WIFI_PASSWORD "YOUR_WIFI_PASSWORD"
#endif

#ifndef IRA_SERVER_HOST
#define IRA_SERVER_HOST "YOUR_SERVER_HOST"
#endif

#ifndef IRA_SERVER_PORT
#define IRA_SERVER_PORT 8765
#endif

#ifndef IRA_SERVER_PATH
#define IRA_SERVER_PATH "/"
#endif
