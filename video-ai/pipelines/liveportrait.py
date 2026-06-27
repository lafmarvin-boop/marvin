"""
LivePortrait (Kuaishou, juillet 2024)
Le meilleur modèle open source pour l'animation de portraits.
Benchmarké spécifiquement contre HeyGen dans le paper original.

Deux usages :
  1. animate_from_driver  : transfert d'expression d'une vidéo source → portrait
  2. animate_expression   : applique des expressions prédéfinies (sourire, clin d'œil…)

Repo : https://github.com/KwaiVGI/LivePortrait
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from typing import Callable

BASE_DIR = Path(__file__).parent.parent
LP_DIR   = BASE_DIR / "LivePortrait"
LP_CKPT  = LP_DIR / "pretrained_weights" / "liveportrait" / "appearance_feature_extractor.safetensors"


def is_available() -> bool:
    return LP_DIR.exists() and LP_CKPT.exists()


def animate_from_image(
    source_image: str,
    output_path: str,
    driving_video: str | None = None,
    expression_preset: str = "talking",
    num_frames: int = 80,
    progress_cb: Callable[[int], None] | None = None,
) -> None:
    """
    Anime un portrait statique avec LivePortrait.

    source_image     : photo du visage à animer
    driving_video    : vidéo dont on transfère les expressions (optionnel)
    expression_preset: "talking" | "smile" | "blink" | "nod"
                       utilisé si pas de driving_video
    num_frames       : nombre de frames à générer
    """
    if not is_available():
        raise RuntimeError("LivePortrait non installé. Lance install.sh.")

    if progress_cb:
        progress_cb(5)

    output_dir = Path(output_path).parent / "lp_tmp"
    output_dir.mkdir(exist_ok=True)

    if driving_video:
        # Mode transfert d'expression depuis une vidéo
        cmd = [
            sys.executable, "inference.py",
            "--source",      source_image,
            "--driving",     driving_video,
            "--output_dir",  str(output_dir),
            "--flag_do_crop",
            "--flag_remap_input",
        ]
    else:
        # Mode expression prédéfinie
        preset_map = {
            "talking":  "assets/examples/driving/d14.mp4",
            "smile":    "assets/examples/driving/d0.mp4",
            "blink":    "assets/examples/driving/d2.mp4",
            "nod":      "assets/examples/driving/d5.mp4",
        }
        template = preset_map.get(expression_preset, preset_map["talking"])
        cmd = [
            sys.executable, "inference.py",
            "--source",     source_image,
            "--driving",    template,
            "--output_dir", str(output_dir),
            "--flag_do_crop",
        ]

    if progress_cb:
        progress_cb(10)

    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        cwd=str(LP_DIR),
    )

    if result.returncode != 0:
        raise RuntimeError(f"LivePortrait échoué :\n{result.stderr[-600:]}")

    if progress_cb:
        progress_cb(85)

    # Récupérer la vidéo générée
    videos = sorted(output_dir.glob("*.mp4"), key=lambda p: p.stat().st_mtime)
    if not videos:
        raise RuntimeError("LivePortrait n'a produit aucune vidéo.")

    import shutil
    shutil.copy(str(videos[-1]), output_path)
    shutil.rmtree(output_dir, ignore_errors=True)

    if progress_cb:
        progress_cb(100)


def animate_with_audio(
    source_image: str,
    audio_path: str,
    output_path: str,
    progress_cb: Callable[[int], None] | None = None,
) -> None:
    """
    Pipeline combiné : LivePortrait (expressions) + MuseTalk (lip sync).
    Résultat : portrait qui parle avec expressions naturelles.
    """
    from pathlib import Path
    import shutil

    tmp_dir = Path(output_path).parent
    lp_out  = str(tmp_dir / "_lp_anim.mp4")

    # 1. LivePortrait génère l'animation de base
    if progress_cb:
        progress_cb(5)

    animate_from_image(
        source_image=source_image,
        output_path=lp_out,
        expression_preset="talking",
        progress_cb=lambda p: progress_cb(int(p * 0.45)) if progress_cb else None,
    )

    # 2. MuseTalk affine le lip sync sur la vidéo LivePortrait
    from .musetalk import is_available as muse_ok, run_musetalk

    if progress_cb:
        progress_cb(50)

    if muse_ok():
        run_musetalk(
            image_path=lp_out,
            audio_path=audio_path,
            output_path=output_path,
            progress_cb=lambda p: progress_cb(50 + int(p * 0.48)) if progress_cb else None,
        )
        Path(lp_out).unlink(missing_ok=True)
    else:
        # Ajouter juste l'audio sur la vidéo LivePortrait
        import subprocess
        subprocess.run([
            "ffmpeg", "-y",
            "-i", lp_out,
            "-i", audio_path,
            "-c:v", "copy",
            "-c:a", "aac", "-b:a", "192k",
            "-shortest",
            output_path,
        ], capture_output=True, check=True)
        Path(lp_out).unlink(missing_ok=True)

    if progress_cb:
        progress_cb(100)
