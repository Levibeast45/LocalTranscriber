## Verified on Levi's PC (2026-10-03)

- RTX 3080 Laptop, 16 GiB VRAM; CTranslate2 detects one CUDA device.
- Whisper large-v3, float16, CUDA; 72.176 s English TikTok audio transcribed in 14.55 s after a 12.25 s model load. This is one sample, not a general speed or accuracy guarantee.
- The actual HTTP link submission completed through download, CUDA transcription and SQLite persistence. Ten service tests pass, including auth, upload, exports, URL validation and explicit CUDA-only initialization.
- The mobile web interface is served on localhost:8765. Private remote access requires Tailscale Serve HTTPS to be enabled by the account owner. No public Funnel configured.
- Windows per-user Startup shortcut launches the service at login. This is not an unattended pre-login Windows service. Runtime and model live outside the repository.
