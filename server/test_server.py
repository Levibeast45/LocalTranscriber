import io
import time
import wave
from pathlib import Path

from fastapi.testclient import TestClient
import pytest
from app import create_app, validate_url


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
