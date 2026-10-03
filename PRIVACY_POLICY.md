# Privacy Policy for LocalTranscriber

Last updated: October 2, 2026

LocalTranscriber is a fork of VoiceSkip. Speech recognition runs on your device
using whisper.cpp. No audio or transcript is uploaded to a transcription service.

## Media links

When you start transcription of a link, yt-dlp and FFmpeg retrieve the media from
the hosting service and its delivery servers. Those services receive normal
network requests, including your IP address and the requested link, under their
own privacy policies. Local-file transcription does not require this download.
No login cookies, accounts, analytics or advertising services are integrated.

Downloaded media is held in private cache storage and deleted after processing,
cancellation or failure. If Android kills the process, temporary files may remain
until the app cache is cleared. Downloaded audio is not retained for listen mode.

## Local storage and permissions

- Internet: retrieve the media from links you submit.
- Foreground service, notifications and wake lock: keep processing visible and
  active in the background.
- System file picker: read only the local media/model files you select. Imported
  files may retain a read grant for later use. No broad storage access is requested.
- Saved transcripts and preferences remain in the app's local storage. You may
  copy or share a transcript yourself using the existing Android controls.
- Delete a saved transcript in the app, or clear app storage/uninstall to remove
  all app data. App backup is disabled.

Source code: https://github.com/Levibeast45/LocalTranscriber
