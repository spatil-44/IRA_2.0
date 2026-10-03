# Ira for Android

This is the phone-first Ira app. The current MVP uses explicit tap-to-talk:
Android records mono PCM at 16 kHz and streams it to the local transcription
server over a WebSocket. The next mobile phase can add a project-trained
on-device “Ira” wake-word model; this app does not use a pretrained wake-word
service or model.

## Build and install

1. Install Android Studio with Android SDK Platform 35 and a JDK supported by
   Android Gradle Plugin 8.7.
2. Open the `phone` folder in Android Studio and allow Gradle to sync.
3. Select a connected Android phone with USB debugging enabled, then select
   **Run** for the `app` configuration.
4. Start the Ira server on a computer on the same trusted private Wi-Fi
   network. In the app, edit **Your local server** and enter its LAN hostname
   or address, for example `ws://YOUR_SERVER_HOST:8765`.
5. Tap **Start talking**, allow microphone access, speak, then tap **Stop
   recording**. The app automatically ends a recording after 30 seconds.

Keep the app open during a recording. The first version intentionally does not
run a background microphone service. Use `wss://` if the server is configured
with TLS; the local starter server uses `ws://`, which Android permits for
private-LAN testing. Do not expose the unauthenticated, unencrypted development
server to public or untrusted networks.

The phone sends raw little-endian PCM frames; the server continues accepting
Opus frames from the ESP32 firmware as well. Audio transcription is performed
by the local faster-whisper server, not by a cloud API.
