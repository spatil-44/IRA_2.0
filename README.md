# Ira

**A low-latency, low-power hybrid edge/cloud voice activation system**
Smart India Hackathon 2026 · ISRO problem statement **SIH26172** · Team **NeuroVox**

Ira uses an on-device, project-trained keyword spotter for the custom wake word
“Ira”. Once activated, the ESP32-S3 streams short Opus packets to a local
Raspberry Pi or Windows server for offline speech transcription with
faster-whisper.

## Architecture

```mermaid
flowchart LR
    Mic[INMP441] -->|16 kHz I2S| ESP[ESP32-S3]
    ESP --> MFCC[MFCC frontend]
    MFCC --> KWS[INT8 DS-CNN / TFLite Micro]
    KWS -->|Wake word detected| Opus[Opus audio stream]
    Opus -->|WebSocket :8765 over local WiFi| Server[Local Pi or Windows server]
    Server --> Decode[Opus decode]
    Decode --> Whisper[faster-whisper, pre-cached model]
    Whisper -->|Transcript JSON| ESP
```

See [docs/architecture.md](./docs/architecture.md) for the full data flow.

## Hardware

- ESP32-S3 DevKitC-1
- INMP441 I2S MEMS microphone
- Local Raspberry Pi or Windows laptop on the same WiFi network

| INMP441 | ESP32-S3 DevKitC-1 |
|---|---|
| VDD | 3V3 |
| GND | GND |
| L/R | GND |
| SCK | GPIO26 |
| WS | GPIO25 |
| SD | GPIO33 |

Detailed notes: [hardware/wiring.md](./hardware/wiring.md).

## Quick start

### Firmware

Install PlatformIO and create a private configuration file (the example has
placeholders and is not usable until edited):

```powershell
Copy-Item .\firmware\include\secrets.example.h .\firmware\include\secrets.h
notepad .\firmware\include\secrets.h
```

Set the WiFi SSID/password and the server's LAN hostname or address in
`secrets.h`. Do not commit it. With the device connected over USB:

```powershell
pio run -d .\firmware
pio run -d .\firmware -t upload
pio device monitor -d .\firmware -b 115200
```

The firmware currently contains a deliberately invalid model placeholder and
will report that KWS is unavailable until the trained model is exported to
`firmware/src/model_data.h`.

### Collect data and train the wake-word model

Use Python 3.10 for the TensorFlow 2.10 Windows-compatible training toolchain.
Create a balanced set of “Ira” utterances and negative speech/noise examples.
All non-`ira` class directories are combined into the `unknown` class:

```powershell
py -3.10 -m venv .venv-ml
.\.venv-ml\Scripts\Activate.ps1
python -m pip install -r .\ml\requirements.txt
python .\ml\collect_dataset.py --label ira --count 100
python .\ml\collect_dataset.py --label unknown --count 100
python .\ml\collect_dataset.py --label noise --count 100
python .\ml\train.py
python .\ml\quantize.py
python .\ml\export_c_array.py
```

Record in varied environments, with multiple speakers, distances, and
background conditions. Preserve participant consent and do not collect
unnecessary personal audio. Inspect validation results and test false
activations before deployment.

### Server on Windows

Use Python 3.10 or newer, then install the server dependencies in a virtual
environment:

```powershell
py -3 -m venv .venv-server
.\.venv-server\Scripts\Activate.ps1
python -m pip install -r .\server\requirements.txt
python .\server\server.py
```

The server binds to `0.0.0.0:8765`. Allow Python through Windows Firewall on
your private WiFi network if prompted. Configure the ESP32 host in `secrets.h`
with the laptop's LAN hostname/address; do not use the public internet address.
The ESP32 and server must be on the same local network.

### Raspberry Pi and offline model caching

On the Pi, create a virtual environment and install `server/requirements.txt`.
Before disconnecting from the internet, download the selected model once:

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r server/requirements.txt
python -c "from faster_whisper import WhisperModel; WhisperModel('small', device='cpu', compute_type='int8')"
```

Set the environment variable before starting the service to prevent network
access:

```sh
HF_HUB_OFFLINE=1 IRA_WHISPER_MODEL=small python server/server.py
```

On Windows, use PowerShell after installing the model:

```powershell
$env:IRA_WHISPER_MODEL = "small"
$env:HF_HUB_OFFLINE = "1"
python .\server\server.py
```

If using a custom Hugging Face cache, set `HF_HOME` to the same directory
during both download and offline operation. `HF_HUB_OFFLINE=1` is passed
through unchanged, so an uncached model fails visibly instead of silently
attempting a download.

## Current metrics

No performance figures have been measured yet. Fill in this table only with
repeatable measurements from the target hardware.

| Metric | Target / constraint | Measured |
|---|---:|---:|
| Wake-word-to-stream latency | TBD | TBD |
| Transcription latency | TBD | TBD |
| Idle CPU utilization | <10% | TBD |
| MCU RAM use | <256 KB | TBD |

## Repository map

- `firmware/` — PlatformIO ESP32-S3 application, I2S capture, MFCC/TFLite
  Micro inference, WebSocket and Opus streaming.
- `ml/` — WAV dataset collection, shared MFCC frontend, DS-CNN training,
  integer quantization, and C-array exporter.
- `server/` — asynchronous WebSocket endpoint and offline faster-whisper
  transcription; see [server/README.md](./server/README.md) for platform setup.
- `hardware/` — wiring table and notes.
- `docs/` — architecture and pipeline documentation.

## Team

**NeuroVox** · Smart India Hackathon 2026 · ISRO problem statement SIH26172.

## License

MIT; see [LICENSE](./LICENSE). Third-party libraries retain their respective
open-source licenses.
