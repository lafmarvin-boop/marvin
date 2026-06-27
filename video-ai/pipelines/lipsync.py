"""
LatentSync lip synchronisation.
Takes a video + audio and generates a lip-synced video.
LatentSync (ByteDance, 2024) significantly outperforms Wav2Lip/SadTalker.

Requires LatentSync to be installed by install.sh in ./LatentSync/
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from typing import Callable


LATSYNC_DIR = Path(__file__).parent.parent / "LatentSync"
CHECKPOINT = LATSYNC_DIR / "checkpoints" / "latentsync_unet.pt"
WHISPER_CKPT = LATSYNC_DIR / "checkpoints" / "whisper" / "tiny.pt"


def is_available() -> bool:
    return LATSYNC_DIR.exists() and CHECKPOINT.exists()


def run_lipsync(
    video_path: str,
    audio_path: str,
    output_path: str,
    progress_cb: Callable[[int], None] | None = None,
) -> bool:
    """
    Apply lip sync to a video using LatentSync.
    Returns True on success, False on failure.
    """
    if not is_available():
        raise RuntimeError(
            "LatentSync non installé. Lance install.sh d'abord."
        )

    if progress_cb:
        progress_cb(10)

    config_path = LATSYNC_DIR / "configs" / "unet" / "second_stage.yaml"

    cmd = [
        sys.executable,
        str(LATSYNC_DIR / "scripts" / "inference.py"),
        "--unet_config_path", str(config_path),
        "--inference_ckpt_path", str(CHECKPOINT),
        "--video_path", video_path,
        "--audio_path", audio_path,
        "--video_out_path", output_path,
    ]

    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        cwd=str(LATSYNC_DIR),
    )

    if progress_cb:
        progress_cb(90)

    if result.returncode != 0:
        raise RuntimeError(f"LatentSync a échoué : {result.stderr[-500:]}")

    return True
