# Ira transcription server

The server accepts WebSocket clients from both the ESP32 and Android app. Each
utterance starts with a JSON `start` control message, sends binary audio, and
ends with a JSON `end` control message. ESP32 audio uses Opus; Android sends
mono 16 kHz signed 16-bit PCM (`pcm_s16le`). The server runs faster-whisper,
sends the transcript to a local Ollama model, and returns the transcript,
assistant reply, and elapsed latencies. Whisper work is serialized so phone
and ESP32 clients can connect at the same time without concurrent inference
on the same model instance.

## Windows

Use Python 3.10 or newer. The PyAV version range in `requirements.txt` keeps
Windows installs on releases with published wheels instead of requiring a
local FFmpeg/C++ build:

```powershell
py -3 -m venv .venv-server
.\.venv-server\Scripts\Activate.ps1
python -m pip install -r .\server\requirements.txt
python .\server\server.py
```

Run these commands from the repository root. If another virtual environment
is active, leave it first with `deactivate` before creating `.venv-server`.

The service listens on `0.0.0.0:8765`. Permit Python through Windows Firewall
for the private WiFi network. Configure the ESP32 address in
`firmware/include/secrets.h` and the Android WebSocket address in the app's
**Raspberry Pi Server** card (for example, `ws://192.168.1.50:8765/`). Keep
both devices and the server on the same trusted LAN, and reserve the server's
DHCP address in your router so it does not change.

The Android `ws://` connection is unencrypted. Use it only on a trusted private
network; do not expose port 8765 to the public internet or forward it from the
router. The ESP32 protocol is also plain WebSocket.

### Local assistant with Ollama

Install Ollama and download a model while online. Ira defaults to the smaller
`tinyllama:latest` model:

```powershell
ollama pull tinyllama:latest
ollama list
```

The Ira server reuses Ollama if it is already running, or starts the local
`ollama serve` process and waits for it before loading Whisper. Install Ollama
and download a model once; Ira does not automatically download models. The
WebSocket server calls Ollama locally; ESP32 audio and transcripts are not sent
to Ollama's cloud. Choose another model already installed in Ollama before
starting the server:

```powershell
$env:IRA_OLLAMA_MODEL = "your-local-model"
python .\server\server.py
```

If Ollama cannot be started or reached, the Ira server reports the reason and
exits instead of starting without assistant replies.

Model quality and response time depend on the computer and model size. The
8B `llama3.1:8b` model may not fit on low-memory machines. Ira shows an explicit
assistant error if Ollama is unavailable or fails to generate a response;
the speech transcript is still returned.

To pre-cache the default `small` model while online:

```powershell
python -c "from faster_whisper import WhisperModel; WhisperModel('small', device='cpu', compute_type='int8')"
```

Run offline after caching:

```powershell
$env:IRA_WHISPER_MODEL = "small"
$env:HF_HUB_OFFLINE = "1"
python .\server\server.py
```

For a custom model cache, set `HF_HOME` consistently while downloading and
running offline. The process fails at model initialization if required model
files are not cached; it will not silently continue online.

## Raspberry Pi

Install Python 3 and create a virtual environment in the repository root:

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r server/requirements.txt
```

Install Ollama for the Pi and pull a model while online:

```sh
ollama pull tinyllama:latest
```

The Ira server starts Ollama automatically if it is not already responding.
The model must be downloaded once before running the server.

While the Pi is online, download the model into the cache:

```sh
python -c "from faster_whisper import WhisperModel; WhisperModel('small', device='cpu', compute_type='int8')"
```

Then run fully offline:

```sh
HF_HUB_OFFLINE=1 IRA_WHISPER_MODEL=small python server/server.py
```

Choose another supported faster-whisper model by setting `IRA_WHISPER_MODEL`
before startup. The corresponding model must also be present in the cache.
`IRA_WHISPER_DEVICE` and `IRA_WHISPER_COMPUTE_TYPE` can override the default
`cpu` and `int8` settings where the hardware supports them.
