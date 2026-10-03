# URL transcription architecture

LocalTranscriber is a GPL-3.0-or-later fork of VoiceSkip. The existing Kotlin,
Compose, Hilt, foreground service, MediaExtractor/MediaCodec and whisper.cpp
pipeline remains the foundation. Native inference code is unchanged; CMake now
locates the host shader compiler bundled with the pinned Android NDK.

## Flow

1. Paste a link, or share text from another Android app. Shared links populate a
   saved draft; the user explicitly starts the download. Exactly one HTTP(S)
   link is required. Unsupported schemes and embedded credentials are rejected.
2. The existing service/repository accepts the URI and starts one transcription
   job. HTTP(S) input is resolved inside FileTranscriptionUseCase, while content
   URIs and local files retain their existing decoder path.
3. UrlMediaDownloader uses youtubedl-android 0.18.1 (bundled yt-dlp/Python and
   FFmpeg). Work runs off the main thread in private cache storage. It requests
   one item, prefers audio, excludes livestreams, and converts to M4A for Android
   decoding. Downloads are limited by yt-dlp to 500 MB and known durations up to
   four hours. These are extractor limits, not a hard disk quota.
4. Download progress uses the existing active transcription state with a
   separate downloading flag, keeping service lifetime, cancellation and
   notifications on the existing path. Then FileAudioProvider feeds whisper.cpp.
5. A finally block releases the decoder and removes the per-job directory on
   completion, failure or cancellation. No network audio URI is stored as a
   playable local file. Results retain the existing copy/share/delete behavior.

## Packaging and privacy

- Application ID: com.levibeast.localtranscriber (can coexist with VoiceSkip).
- Native libraries are extracted because Python/FFmpeg must execute from the
  app native-library directory. ARM64 CI output targets devices such as S24 Ultra.
- Debug APKs include the base, small and VAD models; Git LFS checkout is required.
- Internet permission is for media retrieval; inference is local. No accounts,
  cookies, transcription APIs, analytics or backend server are added.
- Listen mode remains available for local files; URL media is temporary.
- Failed GPU inference can retry the original URL on CPU, downloading it again.
- Process death can leave cache files until Android clears the cache; a job is
  not automatically resumed. Cancellation and normal failures clean immediately.
- The bundled extractor version is pinned. Website changes may require a future
  dependency update. Private/login/DRM media and livestreams are unsupported.

## Verification

CI runs the full JVM test suite, coverage, Android lint and ARM64 APK assembly,
checks the APK signature and uploads the APK plus reports. This does not replace
device testing of native Python/FFmpeg, codecs, GPU drivers or live websites.

On an S24 Ultra, verify: local audio/video; pasted direct media link; a public
video page; Android Share with title + URL; invalid link; offline download;
cancel during download and transcription; app backgrounding; completed text
copy/share/delete; and a second local transcription after a cancelled download.

The APK is debug-signed for personal testing. CI runners generate debug keys,
so separate runs may require uninstalling the previous build (losing app data).
A persistent private release signing key is a separate release-management step.
