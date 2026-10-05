# Ira for Android

Tap-to-talk records mono 16 kHz audio on the phone and streams it over
WebSocket to the configured Ira server. The Pi runs faster-whisper and its
local Ollama model, then returns the transcript and reply. The Android app and
ESP32 can connect to the same server; the server serializes Whisper inference.
The optional private wake-word/device-control mode remains phone-local and
uses Android's on-device speech recognizer and user-enabled Accessibility.

## Build and install

1. Install Android Studio with Android SDK Platform 35 and a JDK supported by
   Android Gradle Plugin 8.13.
2. Open the `phone` folder in Android Studio and allow Gradle to sync.
3. Select a connected Android 12+ phone with USB debugging enabled, then select
   **Run** for the `app` configuration.
4. Connect the phone to the same trusted Wi-Fi network as the Pi. In Ira's
   **Raspberry Pi Server** card, enter and save the server address, such as
   `ws://192.168.1.50:8765/`. Use the Pi's reserved LAN IP, not `localhost`.
5. Tap **Start talking**, allow microphone access, speak, then tap **Stop
   recording**. The phone streams PCM audio to the Pi, where transcription and
   assistant replies run. The server accepts up to 30 seconds per utterance.

The server address uses plain WebSocket (`ws://`) by default, so audio and
transcripts are not encrypted in transit. Use only on a trusted private LAN;
do not expose or port-forward port 8765 to the internet. A server failure is
shown in Ira; the phone does not silently fall back to local transcription.

## Private on-device voice control

1. Tap **Enable app control** and explicitly enable **Ira device voice
   control** in Android Accessibility settings.
2. Tap **Start private wake-word listening** and grant microphone and
   notification permissions. Ira requires Android's on-device speech
   recognizer; if it is unavailable, Ira refuses to start rather than using a
   network recognizer.
3. Say “Ira” followed by a command. A glowing animated Ira orb appears over
   your current app while it listens, then briefly shows the command result.
   Examples: “Ira, open Wi-Fi settings”,
   “Ira, open [app name]”, “Ira, tap [exact visible label]”, “Ira, type
   [text]”, “Ira, scroll down”, or “Ira, go back”. Use **Start talking** for
   questions that need transcription or an assistant reply from the Pi.
4. Stop listening from the app or the persistent notification when finished.

The microphone remains active locally while detecting the wake word. Android
shows a foreground notification and its microphone-use indicator. Accessibility
access is user-enabled; Ira inspects visible controls only when carrying out a
spoken command. The small, non-interactive orb uses that same enabled
Accessibility service to appear over apps; it does not request the separate
draw-over-other-apps permission. Android limits apps from changing many settings directly, so
settings commands open the relevant Android settings screen for you to review
and apply.

The listener uses Android's general on-device speech recognizer rather than a
dedicated low-power keyword model, so wake-word accuracy and battery use vary
by phone. App commands can open installed launchable apps, operate exact
visible labels, enter text in the focused field, scroll, and use basic
navigation controls; they cannot directly read hidden app databases. Text
entered into another app remains under that app's own behavior and privacy
policy.

## Collect custom wake-word samples

In the app's **Train Ira's wake word** card, tap one of the three sample
buttons and immediately say “Ira”, other speech, or stay quiet for background
noise. Each sample is one second of mono 16 kHz PCM saved as WAV under the
app's private external-files directory:

`/sdcard/Android/data/com.neurovox.ira/files/Documents/IraDataset/<label>/`

Collection is manual and foreground-only. These samples remain on the phone
and are never sent to a server. For an initial dataset, aim for at least 100
“Ira” samples and 200 negative samples across other speech and varied
background noise. Include varied rooms, distances, and consenting speakers.
Avoid recording bystanders without their permission.

From the repository root, use Android Debug Bridge to copy samples to the
training dataset:

```powershell
adb pull /sdcard/Android/data/com.neurovox.ira/files/Documents/IraDataset .\ml\phone-captures
New-Item -ItemType Directory -Force .\ml\data | Out-Null
Copy-Item .\ml\phone-captures\IraDataset\* .\ml\data -Recurse -Force
```

The resulting `ml/data/ira`, `ml/data/unknown`, and `ml/data/noise` folders
match the training script's expected structure. The Android app's live wake
listener currently uses Android's on-device recognizer; these custom samples
are not used by it yet.
