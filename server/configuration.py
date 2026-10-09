"""Recover missing launcher configuration without replacing user data or credentials."""
import json
import os
import uuid
from pathlib import Path


def load_config(path):
    path = Path(path)
    backup = path.with_suffix('.json.bak')
    if path.exists():
        config = json.loads(path.read_text(encoding='utf-8-sig'))
    elif backup.exists():
        config = json.loads(backup.read_text(encoding='utf-8-sig'))
    else:
        cache = Path(os.environ.get('HF_HUB_CACHE', str(Path.home() / '.cache/huggingface/hub')))
        snapshots = cache / 'models--Systran--faster-whisper-large-v3/snapshots'
        models = sorted(p for p in snapshots.glob('*')
                        if all((p / name).is_file() for name in ('model.bin', 'config.json', 'tokenizer.json')))
        if not models:
            raise RuntimeError('Configuration absente et modele Large-v3 introuvable dans le cache local. Aucun telechargement automatique effectue.')
        config = {'data': str(path.parent), 'model': str(models[-1]), 'port': 18765}
    if not Path(config['model']).is_dir():
        raise RuntimeError('Le dossier du modele configure est inaccessible : ' + config['model'])
    int(config.get('port', 18765))
    payload = json.dumps(config, ensure_ascii=False)
    path.parent.mkdir(parents=True, exist_ok=True)
    # Atomic replacement avoids a truncated config after an interrupted write.
    for target in ([path] if not path.exists() else []) + [backup]:
        temporary = target.with_suffix(target.suffix + '.' + uuid.uuid4().hex + '.tmp')
        temporary.write_text(payload, encoding='utf-8')
        temporary.replace(target)
    return config
