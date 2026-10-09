"""Console-free Windows launcher; configuration and logs stay outside Git."""
import json
import logging
import os
from pathlib import Path
import sys
from configuration import load_config

config = load_config(Path(sys.argv[1]))
data = Path(config["data"])
data.mkdir(parents=True, exist_ok=True)
os.environ["LT_DATA"] = str(data)
os.environ["LT_MODEL"] = config["model"]
log = (data / "service.log").open("a", encoding="utf-8", buffering=1)
sys.stdout = sys.stderr = log
logging.basicConfig(stream=log, level=logging.INFO)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app:create_app", factory=True, host="127.0.0.1", port=int(config.get("port", 18765)),
                workers=1, access_log=False)
