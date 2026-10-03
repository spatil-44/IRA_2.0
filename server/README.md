# Ira transcription server

The server accepts one JSON `start` control message, binary Opus packets, and a
JSON `end` control message per utterance. It decodes to mono 16 kHz float audio,
runs faster-whisper, and returns a JSON transcript and elapsed latency.

## Windows

Use Python 3.10 or newer:

```powershell
py -3 -m venv .venv-server
.\.venv-server\Scripts\Activate.ps1
python -m pip install -r .\server\requirements.txt
python .\server\server.py
```

The service listens on `0.0.0.0:8765`. Permit Python through Windows Firewall
for the private WiFi network, then set the laptop's LAN hostname/address in
`firmware/include/secrets.h`. Keep the ESP32 and laptop on the same network.

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
