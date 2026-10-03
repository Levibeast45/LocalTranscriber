import io
import json
import logging
import os
import secrets
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import urlsplit

from fastapi import Depends, FastAPI, Header, HTTPException, UploadFile, File, Form
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel, Field

from engine import CudaEngine

LIMIT = 500 * 1024 * 1024
HOSTS = ("tiktok.com", "facebook.com", "fb.watch", "youtube.com", "youtu.be", "vimeo.com")


def validate_url(value):
    value = value.strip()
    u = urlsplit(value)
    host = (u.hostname or "").lower()
    if (u.scheme not in ("https", "http") or u.username or u.password
            or u.port not in (None, 80, 443)
            or not any(host == h or host.endswith("." + h) for h in HOSTS)):
        raise ValueError("Utilise un lien TikTok, Facebook, YouTube ou Vimeo public.")
    return value


def download(url, folder):
    # A child process provides an enforceable timeout and contains extractor errors.
    cmd = [sys.executable, "-m", "yt_dlp", "--no-playlist", "--playlist-items", "1",
           "--socket-timeout", "25", "--retries", "2", "--max-filesize", "500M",
           "--match-filter", "!is_live & duration <=? 7200", "--no-progress",
           "-f", "bestaudio/best", "-o", str(folder / "media.%(ext)s"), url]
    try:
        run = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                             errors="replace", timeout=900,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except subprocess.TimeoutExpired:
        raise RuntimeError("Le téléchargement a dépassé 15 minutes.") from None
    if run.returncode:
        detail = run.stderr.lower()
        if any(s in detail for s in ("login", "sign in", "private", "cookies")):
            message = "Le site demande une connexion ou refuse l’accès. Importe la vidéo enregistrée."
        elif any(s in detail for s in ("403", "429", "impersonat", "unexpected response")):
            message = "Le site bloque la récupération de cette vidéo. Importe le fichier ou réessaie plus tard."
        else:
            message = "Téléchargement impossible : lien indisponible, réseau ou extracteur du site."
        raise RuntimeError(message)
    files = [p for p in folder.glob("media.*") if p.suffix not in (".part", ".ytdl")]
    if len(files) != 1 or files[0].stat().st_size == 0:
        raise RuntimeError("Aucun média récupéré. Vérifie la durée (2 h max) et la taille (500 Mo max).")
    if files[0].stat().st_size > LIMIT:
        raise RuntimeError("Le média dépasse 500 Mo.")
    return files[0]


class LinkRequest(BaseModel):
    url: str = Field(max_length=4096)
    language: str = "auto"


def create_app(data_dir=None, engine=None):
    data = Path(data_dir or os.environ.get("LT_DATA", Path.home() / ".localtranscriber"))
    data.mkdir(parents=True, exist_ok=True)
    token_file = data / "access-token.txt"
    if not token_file.exists():
        token_file.write_text(secrets.token_urlsafe(32), encoding="utf-8")
    token = token_file.read_text(encoding="utf-8").strip()
    db_path = data / "jobs.sqlite3"
    stop = threading.Event()
    engine = engine or CudaEngine(os.environ.get("LT_MODEL", "large-v3"))
    health = {"ready": False, "device": "cuda", "model": "large-v3", "error": None}

    def db():
        c = sqlite3.connect(db_path, timeout=20)
        c.row_factory = sqlite3.Row
        return c

    with db() as c:
        c.execute("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, created REAL, status TEXT, progress INTEGER, source TEXT, language TEXT, result TEXT, error TEXT)")
        c.execute("UPDATE jobs SET status='error', error='Traitement interrompu par le redémarrage du PC. Relance-le.' WHERE status IN ('downloading','transcribing')")

    def update(id, **values):
        with db() as c:
            c.execute("UPDATE jobs SET " + ",".join(k + "=?" for k in values) + " WHERE id=?",
                      (*values.values(), id))

    def worker():
        try:
            engine.load()
            health["ready"] = True
        except Exception:
            logging.exception("CUDA model initialization failed")
            health["error"] = "Impossible de charger large-v3 sur CUDA. Vérifie le modèle et les bibliothèques NVIDIA sur le PC."
            return
        while not stop.is_set():
            with db() as c:
                c.execute("BEGIN IMMEDIATE")
                row = c.execute("SELECT * FROM jobs WHERE status='queued' ORDER BY created LIMIT 1").fetchone()
                if row is not None:
                    c.execute("UPDATE jobs SET status=? WHERE id=?", ("downloading" if row["source"].startswith("http") else "transcribing", row["id"]))
            if row is None:
                stop.wait(0.5)
                continue
            folder = data / row["id"]
            folder.mkdir(exist_ok=True)
            started = time.monotonic()
            try:
                if row["source"].startswith("http"):
                    update(row["id"], status="downloading")
                    path = download(row["source"], folder)
                else:
                    path = folder / "input.media"
                import av
                with av.open(str(path)) as container:
                    if not container.streams.audio:
                        raise RuntimeError("Ce fichier ne contient aucune piste audio.")
                    if container.duration and container.duration / av.time_base > 7200:
                        raise RuntimeError("La durée dépasse 2 heures.")
                update(row["id"], status="transcribing")
                result = engine.transcribe(path, None if row["language"] == "auto" else row["language"],
                                          lambda p: update(row["id"], progress=p))
                result["elapsed_seconds"] = round(time.monotonic() - started, 2)
                update(row["id"], status="done", progress=100, result=json.dumps(result, ensure_ascii=False))
            except Exception as e:
                logging.exception("Job %s failed", row["id"])
                # Only our controlled messages may be exposed. Native errors can contain paths.
                message = str(e) if type(e) is RuntimeError and str(e).startswith(("Le ", "La ", "Ce ", "Aucun", "Téléchargement")) else "Le traitement a échoué sur le PC. Aucun repli CPU n’a été effectué."
                update(row["id"], status="error", error=message)
            finally:
                shutil.rmtree(folder, ignore_errors=True)

    @asynccontextmanager
    async def lifespan(app):
        t = threading.Thread(target=worker, daemon=True)
        t.start()
        yield
        stop.set()
        t.join(timeout=2)

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

    @app.middleware("http")
    async def request_guard(request, call_next):
        if request.url.path.startswith("/api/"):
            if not secrets.compare_digest(request.headers.get("authorization", "").encode(), ("Bearer " + token).encode()):
                return Response('{"detail":"Clé de connexion incorrecte."}', status_code=401, media_type="application/json")
            try:
                length = int(request.headers.get("content-length", "0"))
            except ValueError:
                return Response(status_code=400)
            if length > LIMIT + 1024 * 1024:
                return Response('{"detail":"Fichier trop volumineux (500 Mo max)."}', status_code=413, media_type="application/json")
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        return response

    def auth(authorization: str = Header(default="")):
        if not secrets.compare_digest(authorization.encode(), ("Bearer " + token).encode()):
            raise HTTPException(401, "Clé de connexion incorrecte.")

    def language_check(language):
        if language not in ("fr", "en", "auto"):
            raise HTTPException(400, "Langue non prise en charge.")
        if not health["ready"]:
            raise HTTPException(503, health["error"] or "Chargement du modèle CUDA en cours.")
        with db() as c:
            if c.execute("SELECT count(*) FROM jobs WHERE status IN ('queued','downloading','transcribing')").fetchone()[0] >= 10:
                raise HTTPException(429, "La file est pleine (10 traitements).")

    def insert(id, source, language):
        with db() as c:
            c.execute("INSERT INTO jobs VALUES (?,?, 'queued',0,?,?,NULL,NULL)", (id, time.time(), source, language))
        return {"id": id}

    @app.get("/")
    def index():
        return FileResponse(Path(__file__).parent / "index.html", headers={"Cache-Control": "no-store"})

    @app.get("/api/health", dependencies=[Depends(auth)])
    def status():
        return health

    @app.post("/api/jobs/link", dependencies=[Depends(auth)])
    def link(request: LinkRequest):
        language_check(request.language)
        try:
            url = validate_url(request.url)
        except ValueError as e:
            raise HTTPException(400, str(e)) from None
        return insert(uuid.uuid4().hex, url, request.language)

    @app.post("/api/jobs/file", dependencies=[Depends(auth)])
    def upload(file: UploadFile = File(...), language: str = Form("auto")):
        language_check(language)
        id = uuid.uuid4().hex
        folder = data / id
        folder.mkdir()
        try:
            size = 0
            with (folder / "input.media").open("wb") as out:
                while chunk := file.file.read(1024 * 1024):
                    size += len(chunk)
                    if size > LIMIT:
                        raise HTTPException(413, "Fichier trop volumineux (500 Mo max).")
                    out.write(chunk)
            if size == 0:
                raise HTTPException(400, "Fichier vide.")
            return insert(id, "file", language)
        except Exception:
            shutil.rmtree(folder, ignore_errors=True)
            raise
        finally:
            file.file.close()

    @app.get("/api/jobs", dependencies=[Depends(auth)])
    def jobs():
        with db() as c:
            rows = c.execute("SELECT id,created,status,progress,error,result FROM jobs ORDER BY created DESC LIMIT 100").fetchall()
        return [{**dict(r), "result": json.loads(r["result"]) if r["result"] else None} for r in rows]

    @app.get("/api/jobs/{id}/export/{kind}", dependencies=[Depends(auth)])
    def export(id: str, kind: str):
        with db() as c:
            r = c.execute("SELECT result FROM jobs WHERE id=? AND status='done'", (id,)).fetchone()
        if not r:
            raise HTTPException(404, "Résultat introuvable.")
        text = json.loads(r[0])["text"]
        if kind == "txt":
            payload, mime = text.encode("utf-8"), "text/plain; charset=utf-8"
        elif kind == "docx":
            from docx import Document
            document = Document()
            for line in text.splitlines():
                document.add_paragraph(line)
            buf = io.BytesIO()
            document.save(buf)
            payload, mime = buf.getvalue(), "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        else:
            raise HTTPException(400, "Format inconnu.")
        return Response(payload, media_type=mime, headers={"Content-Disposition": f'attachment; filename="transcription.{kind}"'})

    @app.delete("/api/jobs/{id}", dependencies=[Depends(auth)])
    def delete(id: str):
        with db() as c:
            row = c.execute("SELECT status FROM jobs WHERE id=?", (id,)).fetchone()
            if row and row[0] in ("downloading", "transcribing"):
                raise HTTPException(409, "Attends la fin du traitement avant de le supprimer.")
            c.execute("DELETE FROM jobs WHERE id=?", (id,))
        # Never use an unvalidated identifier as a filesystem path.
        if len(id) == 32 and all(ch in "0123456789abcdef" for ch in id):
            shutil.rmtree(data / id, ignore_errors=True)
        return {"deleted": True}

    return app
