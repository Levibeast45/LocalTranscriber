# PC CUDA service

The phone is a browser client. The PC downloads media, runs Whisper large-v3 through faster-whisper on CUDA FP16, stores job results in SQLite, and exports UTF-8 TXT and DOCX. No transcription CPU fallback exists. Audio decoding and VAD may run on CPU. One worker serializes jobs and keeps the model resident in VRAM.

## Windows setup

```powershell
python -m venv server/.venv
server/.venv/Scripts/python.exe -m pip install -r server/requirements.txt
server/.venv/Scripts/hf.exe download Systran/faster-whisper-large-v3 --include model.bin config.json tokenizer.json preprocessor_config.json vocabulary.json
./server/start.ps1
```

Requires an NVIDIA CUDA-capable GPU, compatible driver, and enough available VRAM. NVIDIA DLLs from the virtual environment are added to the loader path explicitly. Model downloads are a setup step: the running service uses local files only. Set `-Model` to the downloaded snapshot directory if using a custom cache location. FFmpeg on PATH is recommended for URL media handling. A failed CUDA initialization rejects jobs instead of selecting CPU.

Open `http://127.0.0.1:18765` on the PC. The access key is generated in `%LOCALAPPDATA%/LocalTranscriber/access-token.txt`. Do not commit or publish it. The browser stores it for the current session only.

## Phone, including mobile data

Install Tailscale on both devices and sign in to the same private network. Once connected, use:

```powershell
tailscale serve --bg http://127.0.0.1:18765
```

Follow Tailscale's HTTPS enablement prompt if needed. Open the private HTTPS address printed by Tailscale on the phone and enter the access key. Keep Tailscale connected. Do not enable Funnel or expose port 18765 to the public internet. This setup does not require a router port forward. Network setup and account login must be completed before mobile-data access works.

## Behavior and limitations

- Supported link entry points: TikTok, Facebook, YouTube and Vimeo. The PC includes curl-cffi for browser impersonation; site availability, authentication and anti-bot restrictions can still prevent extraction.
- Automatic language detection is the default; French and English can be selected explicitly. Results remain verbatim, with no LLM rewrite.
- The queue survives browser closure. Queued jobs resume after server restart; interrupted jobs are marked failed and need resubmission.
- Temporary media is deleted after success/failure. Transcript history remains until deleted. There is no in-progress cancellation in this first PC version.
- Uploads are capped at 500 MiB, media duration at two hours when available, and active jobs at ten. Use only within your private Tailscale network; it is a single-user service, not hardened multi-tenant hosting.
- The old Android APK still runs its local pipeline. This browser interface is the new PC client; it does not yet receive Android Share intents or record the microphone.

Run `python -m pytest server/test_server.py` with the server directory on PYTHONPATH. Tests use a fake engine and do not prove GPU quality. A real CUDA transcription is separately required before claiming hardware readiness.
