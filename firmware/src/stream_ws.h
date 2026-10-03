#pragma once

#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

bool stream_ws_is_active();
bool stream_ws_wants_audio();
void stream_ws_run(QueueHandle_t audioQueue);
