import io
import time
import wave
from pathlib import Path

from fastapi.testclient import TestClient
import pytest
from app import create_app, validate_url


def test_launcher_auth_failure_does_not_start_another_server(tmp_path, monkeypatch):
    import launch
    import urllib.error
    monkeypatch.setattr(launch.sys, 'argv', ['launch.py', str(tmp_path / 'config.json')])
    monkeypatch.setattr(launch, 'load_config', lambda _: {'data': str(tmp_path), 'port': 18765})
    (tmp_path / 'access-token.txt').write_text('test-key')
    def denied(*args, **kwargs):
        raise urllib.error.HTTPError('http://localhost', 401, 'Unauthorized', {}, None)
    monkeypatch.setattr(launch.urllib.request, 'urlopen', denied)
    monkeypatch.setattr(launch.subprocess, 'Popen', lambda *a, **kw: pytest.fail('Duplicate server started'))
    with pytest.raises(RuntimeError, match='cle locale ne correspond pas'):
        launch.main()


def test_missing_config_restored_from_backup(tmp_path):
    import json
    from configuration import load_config
    model = tmp_path / 'model'
    model.mkdir()
    config = {'data': str(tmp_path), 'model': str(model), 'port': 18765}
    path = tmp_path / 'config.json'
    path.write_text(json.dumps(config))
    load_config(path)
    path.unlink()
    assert load_config(path) == config
    assert json.loads(path.read_text()) == config


def test_missing_config_recovers_cached_model(tmp_path, monkeypatch):
    from configuration import load_config
    cache = tmp_path / 'cache'
    monkeypatch.setenv('HF_HUB_CACHE', str(cache))
    model = cache / 'models--Systran--faster-whisper-large-v3/snapshots/test'
    model.mkdir(parents=True)
    for name in ('model.bin', 'config.json', 'tokenizer.json'):
        (model / name).write_text('test')
    path = tmp_path / 'data/config.json'
    assert load_config(path)['model'] == str(model)
    assert path.exists()


def test_missing_config_missing_model_is_explicit(tmp_path, monkeypatch):
    from configuration import load_config
    monkeypatch.setenv('HF_HUB_CACHE', str(tmp_path / 'empty'))
    with pytest.raises(RuntimeError, match='Large-v3 introuvable'):
        load_config(tmp_path / 'config.json')


class FakeEngine:
    def load(self):
        pass

    def transcribe(self, path, language, progress):
        progress(50)
        return {"text": "Été & café.\n\nBonjour !", "device": "cuda", "model": "test"}


def test_auth_upload_queue_exports(tmp_path):
    app = create_app(tmp_path, FakeEngine())
    headers = {"Authorization": "Bearer " + (tmp_path / "access-token.txt").read_text()}
    with TestClient(app) as client:
        assert client.get('/api/jobs').status_code == 401
        assert client.get('/api/jobs', headers={"Authorization": "Bearer wrong"}).status_code == 401
        for _ in range(50):
            if client.get('/api/health', headers=headers).json()['ready']:
                break
            time.sleep(.02)
        assert client.post('/api/jobs/link', headers=headers, json={"url": "http://127.0.0.1"}).status_code == 400
        b = io.BytesIO()
        with wave.open(b, 'wb') as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(b'\0\0' * 16000)
        r = client.post('/api/jobs/file', headers=headers, files={"file": ('a.wav', b.getvalue())})
        assert r.status_code == 200
        id = r.json()['id']
        for _ in range(100):
            job = client.get('/api/jobs', headers=headers).json()[0]
            if job['status'] in ('done', 'error'):
                break
            time.sleep(.03)
        assert job['status'] == 'done', job
        assert job['result']['device'] == 'cuda'
        assert not (tmp_path / id).exists()
        r = client.get(f'/api/jobs/{id}/export/txt', headers=headers)
        assert 'Été & café.' in r.text
        from docx import Document
        r = client.get(f'/api/jobs/{id}/export/docx', headers=headers)
        assert Document(io.BytesIO(r.content)).paragraphs[0].text == 'Été & café.'
        assert client.delete('/api/jobs/'+id, headers=headers).status_code == 200
        assert client.get('/api/jobs', headers=headers).json() == []


def test_cuda_failure_does_not_accept_jobs(tmp_path):
    class Broken:
        def load(self):
            raise RuntimeError('CUDA missing')
    with TestClient(create_app(tmp_path, Broken())) as c:
        h = {"Authorization": 'Bearer ' + (tmp_path/'access-token.txt').read_text()}
        assert c.post('/api/jobs/link', headers=h, json={"url": 'https://vt.tiktok.com/a/'}).status_code == 503


@pytest.mark.parametrize('url', ['file:///secret', 'http://localhost', 'https://facebook.com.evil.test/a', 'https://user:pass@facebook.com/a', 'https://facebook.com:9000'])
def test_rejects_invalid_sources(url):
    with pytest.raises(ValueError):
        validate_url(url)


def test_accepts_share_links():
    assert validate_url('https://vt.tiktok.com/ZSbfM8dxu/')
    assert validate_url('https://www.facebook.com/share/v/example/')


def test_engine_requires_cuda(monkeypatch):
    import sys
    from types import SimpleNamespace
    from engine import CudaEngine
    monkeypatch.setitem(sys.modules, 'ctranslate2', SimpleNamespace(get_cuda_device_count=lambda: 0))
    with pytest.raises(RuntimeError, match='CUDA indisponible'):
        CudaEngine('unused').load()


def test_engine_loads_cuda_fp16_only(monkeypatch):
    import sys
    from types import SimpleNamespace
    from engine import CudaEngine
    calls = []
    monkeypatch.setitem(sys.modules, 'ctranslate2', SimpleNamespace(get_cuda_device_count=lambda: 1))
    def load(path, **kwargs):
        calls.append(kwargs)
        return SimpleNamespace(model=SimpleNamespace(device='cuda'))
    monkeypatch.setitem(sys.modules, 'faster_whisper', SimpleNamespace(WhisperModel=load))
    CudaEngine('local-model').load()
    assert calls == [dict(device='cuda', compute_type='float16', local_files_only=True, num_workers=1)]


def test_startup_removes_orphan_media_only(tmp_path):
    orphan = tmp_path / ('a' * 32)
    orphan.mkdir()
    (orphan / 'media.mp3').write_bytes(b'temporary')
    keep = tmp_path / 'personal'
    keep.mkdir()
    create_app(tmp_path, FakeEngine())
    assert not orphan.exists()
    assert keep.exists()
