"""
Talking head pipeline — portrait photo + audio → realistic video.
Priority order (best → fallback):
  1. Hallo2   (Fudan University, 2024) — best quality, natural expressions
  2. EchoMimic (ByteDance, 2024)       — excellent, well maintained
  3. LatentSync                        — lip sync only, always available

Usage: generate_talking_head(image, audio, output)
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from typing import Callable


BASE_DIR = Path(__file__).parent.parent
HALLO2_DIR  = BASE_DIR / "Hallo2"
ECHOMIMIC_DIR = BASE_DIR / "EchoMimic"
LATSYNC_DIR  = BASE_DIR / "LatentSync"


# ─── Availability checks ──────────────────────────────────────────────────────

def hallo2_available() -> bool:
    ckpt = HALLO2_DIR / "pretrained_models" / "hallo2" / "net.pth"
    return HALLO2_DIR.exists() and ckpt.exists()


def echomimic_available() -> bool:
    ckpt = ECHOMIMIC_DIR / "pretrained_weights" / "denoising_unet.pth"
    return ECHOMIMIC_DIR.exists() and ckpt.exists()


def latsync_available() -> bool:
    ckpt = LATSYNC_DIR / "checkpoints" / "latentsync_unet.pt"
    return LATSYNC_DIR.exists() and ckpt.exists()


def best_available() -> str:
    if hallo2_available():
        return "hallo2"
    if echomimic_available():
        return "echomimic"
    if latsync_available():
        return "latsync"
    return "none"


# ─── Hallo2 ───────────────────────────────────────────────────────────────────

def _run_hallo2(
    image_path: str,
    audio_path: str,
    output_path: str,
    progress_cb: Callable[[int], None] | None = None,
):
    """
    Hallo2: photo + audio → talking head with natural expressions.
    Generates realistic head movements, eye blinking, micro-expressions.
    """
    if progress_cb: progress_cb(5)

    config = HALLO2_DIR / "configs" / "inference" / "long.yaml"
    script = HALLO2_DIR / "scripts" / "inference.py"

    cmd = [
        sys.executable, str(script),
        "--config", str(config),
        "--source_image", image_path,
        "--driving_audio", audio_path,
        "--output", output_path,
        "--pose_weight", "1.0",
        "--face_weight", "1.0",
        "--lip_weight", "1.0",
        "--face_expand_ratio", "1.2",
    ]

    if progress_cb: progress_cb(10)

    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        cwd=str(HALLO2_DIR),
    )

    if result.returncode != 0:
        raise RuntimeError(f"Hallo2 échoué : {result.stderr[-500:]}")

    if progress_cb: progress_cb(90)


# ─── EchoMimic ────────────────────────────────────────────────────────────────

def _run_echomimic(
    image_path: str,
    audio_path: str,
    output_path: str,
    progress_cb: Callable[[int], None] | None = None,
):
    """
    EchoMimic (ByteDance): audio-driven portrait animation.
    Excellent lip sync + natural head movements.
    """
    if progress_cb: progress_cb(5)

    script = ECHOMIMIC_DIR / "infer_audio2vid.py"
    config = ECHOMIMIC_DIR / "configs" / "prompts" / "animation.yaml"

    cmd = [
        sys.executable, str(script),
        "--config", str(config),
        "--source_image", image_path,
        "--driving_audio", audio_path,
        "--output_video_path", output_path,
        "--width", "512",
        "--height", "512",
        "--fps", "24",
        "--inference_steps", "30",
    ]

    if progress_cb: progress_cb(10)

    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        cwd=str(ECHOMIMIC_DIR),
    )

    if result.returncode != 0:
        raise RuntimeError(f"EchoMimic échoué : {result.stderr[-500:]}")

    if progress_cb: progress_cb(90)


# ─── LatentSync fallback ──────────────────────────────────────────────────────

def _run_latsync(
    image_path: str,
    audio_path: str,
    output_path: str,
    progress_cb: Callable[[int], None] | None = None,
):
    import shutil, tempfile

    if progress_cb: progress_cb(5)

    # Create a static video from the portrait first
    tmp_video = str(Path(output_path).parent / "_static.mp4")
    cmd_static = [
        "ffmpeg", "-y",
        "-loop", "1", "-i", image_path,
        "-i", audio_path,
        "-c:v", "libx264", "-tune", "stillimage",
        "-c:a", "aac", "-b:a", "192k",
        "-pix_fmt", "yuv420p", "-shortest",
        "-vf", "scale=512:512:force_original_aspect_ratio=decrease,pad=512:512:(ow-iw)/2:(oh-ih)/2",
        tmp_video,
    ]
    subprocess.run(cmd_static, capture_output=True, check=True)

    if progress_cb: progress_cb(20)

    config_path = LATSYNC_DIR / "configs" / "unet" / "second_stage.yaml"
    ckpt_path   = LATSYNC_DIR / "checkpoints" / "latentsync_unet.pt"

    cmd = [
        sys.executable,
        str(LATSYNC_DIR / "scripts" / "inference.py"),
        "--unet_config_path", str(config_path),
        "--inference_ckpt_path", str(ckpt_path),
        "--video_path", tmp_video,
        "--audio_path", audio_path,
        "--video_out_path", output_path,
    ]

    result = subprocess.run(cmd, capture_output=True, text=True, cwd=str(LATSYNC_DIR))
    Path(tmp_video).unlink(missing_ok=True)

    if result.returncode != 0:
        raise RuntimeError(f"LatentSync échoué : {result.stderr[-500:]}")

    if progress_cb: progress_cb(90)


# ─── Unified entry point ──────────────────────────────────────────────────────

def generate_talking_head(
    image_path: str,
    audio_path: str,
    output_path: str,
    force_model: str = "auto",
    progress_cb: Callable[[int], None] | None = None,
) -> str:
    """
    Generate a realistic talking head video from portrait + audio.
    Auto-selects the best available model.

    Returns the model name used.
    """
    model = force_model if force_model != "auto" else best_available()

    if model == "none":
        raise RuntimeError(
            "Aucun modèle talking head disponible. Lance install.sh."
        )

    if model == "hallo2":
        _run_hallo2(image_path, audio_path, output_path, progress_cb)
    elif model == "echomimic":
        _run_echomimic(image_path, audio_path, output_path, progress_cb)
    elif model == "latsync":
        _run_latsync(image_path, audio_path, output_path, progress_cb)
    else:
        raise RuntimeError(f"Modèle inconnu : {model}")

    if progress_cb: progress_cb(100)
    return model
