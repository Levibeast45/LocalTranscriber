"""Interactive launcher: reuse/start service, await readiness, then open UI."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
import urllib.error
import webbrowser
from configuration import load_config


def main():
    config_path = Path(sys.argv[1]).resolve()
    config = load_config(config_path)
    data = Path(config["data"])
    url = f'http://127.0.0.1:{int(config.get("port", 18765))}'

    def health():
        try:
            token = (data / "access-token.txt").read_text(encoding="utf-8").strip()
            request = urllib.request.Request(url + "/api/health", headers={"Authorization": "Bearer " + token})
            with urllib.request.urlopen(request, timeout=2) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code in (401, 403):
                raise RuntimeError('Le serveur repond mais la cle locale ne correspond pas. Verifie que les lanceurs utilisent le meme dossier de donnees : ' + str(data)) from None
            raise RuntimeError('Le serveur repond avec une erreur HTTP ' + str(error.code)) from None
        except (OSError, ValueError):
            return None

    if health() is None:
        data.mkdir(parents=True, exist_ok=True)
        with (data / "launcher.log").open("a", encoding="utf-8") as log:
            python = str(Path(sys.executable).with_name("python.exe")) if os.name == "nt" else sys.executable
            subprocess.Popen([python, str(Path(__file__).with_name("supervise.py")), str(config_path)],
                             stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                             creationflags=subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP)
    print("LocalTranscriber : verification du serveur et chargement CUDA...", flush=True)
    for _ in range(90):
        state = health()
        if state and state.get("error"):
            raise RuntimeError(state["error"])
        if state and state.get("ready"):
            print("Pret sur CUDA : " + url, flush=True)
            if "--no-browser" not in sys.argv:
                webbrowser.open(url)
            return
        time.sleep(2)
    raise RuntimeError(f"Le serveur ne repond pas. Journaux : {data / 'launcher.log'} et {data / 'service.log'}")


if __name__ == "__main__":
    if sys.stdout is None or sys.stderr is None:
        log_dir = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "LocalTranscriber"
        log_dir.mkdir(parents=True, exist_ok=True)
        sys.stdout = sys.stderr = (log_dir / "startup.log").open("a", encoding="utf-8", buffering=1)
    try:
        main()
    except Exception as error:
        print("Echec du lancement : " + str(error), flush=True)
        sys.exit(1)
