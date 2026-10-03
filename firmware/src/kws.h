#pragma once

#include <stdint.h>

bool kws_begin();
bool kws_detect(const int16_t* window, float& iraScore);
