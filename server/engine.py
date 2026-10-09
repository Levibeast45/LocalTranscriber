"""Single GPU worker. Never fall back to CPU inference."""
import os
import sys
from pathlib import Path

_dll_handles = []


def configure_cuda():
    if sys.platform == "win32":
        for root in sys.path:
            for directory in Path(root).glob("nvidia/*/bin"):
                _dll_handles.append(os.add_dll_directory(str(directory)))
                os.environ["PATH"] = str(directory) + os.pathsep + os.environ["PATH"]


class CudaEngine:
    def __init__(self, model_path):
        self.model_path = model_path
        self.model = None

    def load(self):
        configure_cuda()
        import ctranslate2
        if ctranslate2.get_cuda_device_count() < 1:
            raise RuntimeError("CUDA indisponible. Aucun traitement sur CPU ne sera lancé.")
        from faster_whisper import WhisperModel
        self.model = WhisperModel(self.model_path, device="cuda", compute_type="float16",
                                 local_files_only=True, num_workers=1)
        if self.model.model.device != "cuda":
            raise RuntimeError("Le modèle ne fonctionne pas sur CUDA.")

    def transcribe(self, path, language, progress):
        if self.model is None:
            self.load()
        segments, info = self.model.transcribe(
            str(path), language=language, beam_size=5, vad_filter=True,
            condition_on_previous_text=False, word_timestamps=False,
        )
        result = []
        for s in segments:
            result.append({"start": s.start, "end": s.end, "text": s.text.strip()})
            progress(min(99, int(100 * s.end / max(info.duration, 1))))
        return {"text": "\n\n".join(s["text"] for s in result), "segments": result,
                "language": info.language, "audio_seconds": info.duration,
                "device": "cuda", "compute_type": "float16", "model": "large-v3"}
