# INMP441 wiring

Connect the INMP441 to the ESP32-S3 DevKitC-1 as follows. Power the microphone from 3.3 V only.

| INMP441 pin | ESP32-S3 pin | Notes |
|---|---|---|
| VDD | 3V3 | Do not connect to 5 V |
| GND | GND | Common ground |
| L/R | GND | Selects the left I2S channel |
| SCK | GPIO26 | I2S bit clock |
| WS | GPIO25 | I2S word select / left-right clock |
| SD | GPIO33 | I2S serial data into the ESP32-S3 |

The firmware configures mono, 32-bit I2S input at a 16 kHz sample rate, then
converts the INMP441's left-aligned samples to signed 16-bit PCM.
