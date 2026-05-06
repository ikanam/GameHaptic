# GameHaptic

GameHaptic is an Android app that converts game background audio into real-time haptic feedback. It uses Android `AudioPlaybackCapture` to capture playback audio, analyzes the signal locally, and maps detected impacts, bass pulses, and transients into vibration patterns.

[简体中文](README.zh-CN.md)

## Features

- Captures game audio with Android `AudioPlaybackCapture`
- Runs as a foreground service for continuous background capture
- Uses an invisible overlay window as an optional keep-alive aid
- Filters quiet sounds, steady background music, and low-level noise
- Maps audio intensity to varied haptic strength and waveform patterns
- Provides haptic tuning controls for silence gate, sensitivity, and music filtering
- Supports English, Simplified Chinese, Traditional Chinese, Japanese, and Korean

## Requirements

- Android 10 or later, API 29+
- A device with a vibrator
- For best results, a device with vibration amplitude control
- Android Studio or the included Gradle wrapper

Some games may opt out of playback capture. In that case, Android will not allow this app to capture their audio.

## Permissions

GameHaptic requests these permissions:

- Audio playback capture: used to capture game playback audio through the system capture API
- Overlay permission: used to create a transparent keep-alive window while running behind a game
- Notification permission: required on newer Android versions for the foreground service notification
- Vibration permission: used to output haptic feedback

## Build

Clone the repository:

```bash
git clone git@github.com:ikanam/GameHaptic.git
cd GameHaptic
```

Build a debug APK:

```bash
./gradlew assembleDebug
```

Run unit tests:

```bash
./gradlew testDebugUnitTest
```

## Project Structure

- `app/src/main/java/top/jarman/gamehaptic/MainActivity.kt`: Compose UI, permissions, capture controls, and tuning controls
- `app/src/main/java/top/jarman/gamehaptic/audio/HapticAudioAnalyzer.kt`: audio-to-haptic analysis logic
- `app/src/main/java/top/jarman/gamehaptic/service/AudioCaptureService.kt`: foreground audio capture service and vibration output
- `app/src/main/res/values*/strings.xml`: localized app strings

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
