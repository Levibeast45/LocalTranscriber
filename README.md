# LocalTranscriber

Android app for local audio/video transcription using whisper.cpp, forked from [VoiceSkip](https://github.com/tguillem/VoiceSkip).

## Features

- Audio file transcription (via share or file picker, also extracts audio from
  video files)
- Multiple Whisper model sizes (base, small)
- GPU acceleration via Vulkan 1.2+
- Turbo mode (GPU + CPU) can achieve arround 4x realtime speed with the larger
  model (ggml-small-q8_0)
- Background transcription with foreground service
- Listen mode: play audio and review your transcription
- Local transcription; internet is used only when retrieving URL media
- Paste a media link or use Android Share → LocalTranscriber

## Install on S24 Ultra

Open the latest successful **Android APK** run in this repository's Actions tab.
Download **LocalTranscriber-arm64-debug**, extract the ZIP and install the APK
on your phone. Allow installation from the app you use to open the APK when
Android asks. No root is required. The models are bundled, so the APK is large.

Open LocalTranscriber, wait for the model to load, then choose a local file or
paste a public media link and tap **Transcribe link**. You can also share a link
from another app; review the prefilled link and tap **Transcribe link**.

After transcription, **Copy all** copies the complete formatted text. **Export
TXT** saves UTF-8 text and **Export Word** saves a real `.docx` document, with
paragraph breaks preserved. Both exports open Android's save dialog so you can
choose the destination. Exporting runs locally and needs no extra storage permission.

The APK has a separate identity from VoiceSkip. This is a debug build for
personal testing; see [architecture and limitations](docs/url-transcription.md),
including signing-key differences between CI runs.

## Supported Languages

Arabic, Chinese, Dutch, English, French, German, Italian, Japanese, Korean,
Polish, Portuguese, Russian, Spanish, Swedish, Turkish

## Requirements

- Android 13+ (API 33)

## Build

```bash
./gradlew assembleDebug
```

Use `-PtargetAbi=<abi>` to build for a specific architecture (e.g.,
`arm64-v8a`, `armeabi-v7a`, `x86_64`).

## Test

```bash
./gradlew testDebugUnitTest
./gradlew connectedAndroidTest -PtargetAbi=arm64-v8a
```

## Benchmark

Run transcription benchmarks and print results to console:

```bash
./gradlew benchmark -PtargetAbi=arm64-v8a
```

By default runs all configurations. With custom args, runs 1 test.

Optional arguments:
- `gpu` - enable Vulkan GPU acceleration (default: true)
- `foreground` - launch activity for GPU foreground mode (default: false)
- `turbo` - enable CPU and GPU
- `vad` - enable VAD (default: true)

Example with custom settings:
```bash
./gradlew benchmark -PtargetAbi=arm64-v8a \
    -Pandroid.testInstrumentationRunnerArguments.gpu=true \
    -Pandroid.testInstrumentationRunnerArguments.foreground=true

./gradlew benchmark -PtargetAbi=arm64-v8a \
    -Pandroid.testInstrumentationRunnerArguments.turbo=true \
    -Pandroid.testInstrumentationRunnerArguments.foreground=true \
    -Pandroid.testInstrumentationRunnerArguments.vad=false
```

## Project Structure

- `app/` - Android app (Kotlin, Jetpack Compose)
- `lib/` - whisper.cpp JNI bindings
  - `lib/src/main/jni/` - C/JNI code
  - whisper.cpp fetched via CMake FetchContent
- `models_pack/` - Whisper + VAD models (install-time Play asset pack)

## AI

This kotlin code was developed with AI assistance. All code has been reviewed,
tested, and refined by the author.

## License

Copyright (C) 2025-2026 Thomas Guillem

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

See [LICENSE](LICENSE) for details.
