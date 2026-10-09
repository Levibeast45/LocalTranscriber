"""Windows per-user supervisor. Start at login; never touch other listeners."""
import json
import msvcrt
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
from configuration import load_config

config_path = Path(sys.argv[1]).resolve()
config = load_config(config_path)
data = Path(config["data"])
data.mkdir(parents=True, exist_ok=True)
lock = (data / "supervisor.lock").open("a+b")
lock.seek(0)
try:
    msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
except OSError:
    sys.exit(0)
port = int(config.get("port", 18765))
tailscale = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Tailscale/tailscale.exe"
flags = subprocess.CREATE_NO_WINDOW
with (data / "supervisor.log").open("a", encoding="utf-8", buffering=1) as log:
    while True:
        # Do not claim a port already in use, or launch multiple model workers.
        with socket.socket() as probe:
            occupied = probe.connect_ex(("127.0.0.1", port)) == 0
        if occupied:
            log.write("Port already occupied; supervisor will not modify its owner.\n")
            time.sleep(30)
            continue
        child = subprocess.Popen([sys.executable, str(Path(__file__).with_name("run.py")), str(config_path)],
                                 stdout=log, stderr=log, creationflags=flags)
        for attempt in range(12):
            if child.poll() is not None:
                break
            try:
                result = subprocess.run([str(tailscale), "serve", "--bg", f"http://127.0.0.1:{port}"],
                                        stdout=log, stderr=log, timeout=20, creationflags=flags)
                if result.returncode == 0:
                    break
            except (OSError, subprocess.TimeoutExpired) as e:
                log.write(f"Private routing not ready: {type(e).__name__}\n")
            time.sleep(10)
        code = child.wait()
        log.write(f"Server exited ({code}); retrying in 15 seconds.\n")
        time.sleep(15)
