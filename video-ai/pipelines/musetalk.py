"""
MuseTalk (ByteDance / TMElyralab, 2024)
Lip sync de qualité production — utilisé en interne chez ByteDance.
Nettement supérieur à LatentSync sur les benchmarks publics.

Pipeline :  portrait + audio → vidéo lip-syncée en temps réel
Repo    :   https://github.com/TMElyralab/MuseTalk
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from typing import Callable

BASE_DIR   = Path(__file__).parent.parent
MUSE_DIR   = BASE_DIR / "MuseTalk"
MUSE_CKPT  = MUSE_DIR / "models" / "musetalk" / "musetalk.json"


def is_available() -> bool:
    return MUSE_DIR.exists() and MUSE_CKPT.exists()


def run_musetalk(
    image_path: str,
    audio_path: str,
    output_path: str,
    bbox_shift: int = 0,
    progress_cb: Callable[[int], None] | None = None,
) -> None:
    """
    Génère une vidéo lip-syncée depuis un portrait + audio avec MuseTalk.

    bbox_shift : décalage vertical de la bouche (-10 → 10).
                 0 = auto, valeurs négatives montent la bouche.
    """
    if not is_available():
        raise RuntimeError("MuseTalk non installé. Lance install.sh.")

    if progress_cb:
        progress_cb(5)

    result_dir = Path(output_path).parent / "muse_tmp"
    result_dir.mkdir(exist_ok=True)

    cmd = [
        sys.executable, "-m", "scripts.inference",
        "--unet_model_path",   str(MUSE_DIR / "models" / "musetalk" / "musetalk.json"),
        "--vae_model_path",    str(MUSE_DIR / "models" / "sd-vae-ft-mse"),
        "--whisper_model_dir", str(MUSE_DIR / "models" / "whisper"),
        "--video_path",        image_path,   # accepte aussi une image fixe
        "--audio_path",        audio_path,
        "--result_dir",        str(result_dir),
        "--fps",               "25",
        "--bbox_shift",        str(bbox_shift),
        "--use_float16",
    ]

    if progress_cb:
        progress_cb(10)

    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        cwd=str(MUSE_DIR),
    )

    if result.returncode != 0:
        raise RuntimeError(f"MuseTalk échoué :\n{result.stderr[-600:]}")

    if progress_cb:
        progress_cb(85)

    # MuseTalk écrit dans result_dir — on récupère le dernier .mp4
    videos = sorted(result_dir.glob("*.mp4"), key=lambda p: p.stat().st_mtime)
    if not videos:
        raise RuntimeError("MuseTalk n'a produit aucune vidéo.")

    import shutil
    shutil.copy(str(videos[-1]), output_path)
    shutil.rmtree(result_dir, ignore_errors=True)

    if progress_cb:
        progress_cb(100)
