param(
    [string]$Python = "$PSScriptRoot/.venv/Scripts/python.exe",
    [string]$Model = "large-v3",
    [string]$Data = "$env:LOCALAPPDATA/LocalTranscriber",
    [int]$Port = 18765
)
$ErrorActionPreference = 'Stop'
$env:LT_MODEL = $Model
$env:LT_DATA = $Data
# One process owns the GPU and durable queue. Bind privately behind Tailscale Serve.
& $Python -m uvicorn app:create_app --factory --app-dir $PSScriptRoot --host 127.0.0.1 --port $Port --workers 1
exit $LASTEXITCODE
