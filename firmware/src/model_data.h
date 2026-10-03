#pragma once

#include <stdint.h>

// Replace this deliberately invalid placeholder with export_c_array.py output.
alignas(16) const unsigned char g_model_data[] = {
    0x00, 0x00, 0x00, 0x00,
};
constexpr unsigned int g_model_data_len = sizeof(g_model_data);
constexpr bool g_model_data_is_placeholder = true;
