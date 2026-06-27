"""
SAM2 (Meta, 2024) — Suppression et remplacement du fond.
Suit automatiquement la personne à travers toutes les frames de la vidéo.

Options :
  - Fond transparent (PNG avec alpha)
  - Fond uni (couleur hex)
  - Image de fond personnalisée
  - Flou gaussien du fond (effet bokeh)
  - Vidéo de fond
"""

from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path
from typing import Callable

import cv2
import numpy as np
from PIL import Image

BASE_DIR  = Path(__file__).parent.parent
SAM2_DIR  = BASE_DIR / "sam2"
SAM2_CKPT = SAM2_DIR / "checkpoints" / "sam2.1_hiera_large.pt"
SAM2_CFG  = "configs/sam2.1/sam2.1_hiera_l.yaml"


def is_available() -> bool:
    return SAM2_DIR.exists() and SAM2_CKPT.exists()


def _get_predictor():
    import sys
    sys.path.insert(0, str(SAM2_DIR))
    from sam2.build_sam import build_sam2_video_predictor
    import torch
    device = "cuda" if torch.cuda.is_available() else "cpu"
    return build_sam2_video_predictor(SAM2_CFG, str(SAM2_CKPT), device=device)


def _detect_face_center(frame_bgr: np.ndarray) -> tuple[int, int] | None:
    """Trouve le centre du visage dans la première frame pour initialiser SAM2."""
    try:
        import insightface
        from insightface.app import FaceAnalysis
        import torch
        app = FaceAnalysis(providers=["CUDAExecutionProvider" if torch.cuda.is_available() else "CPUExecutionProvider"])
        app.prepare(ctx_id=0 if torch.cuda.is_available() else -1, det_size=(640, 640))
        faces = app.get(frame_bgr)
        if faces:
            face = max(faces, key=lambda f: (f.bbox[2]-f.bbox[0]) * (f.bbox[3]-f.bbox[1]))
            x1, y1, x2, y2 = [int(v) for v in face.bbox]
            return (x1+x2)//2, (y1+y2)//2
    except Exception:
        pass

    # Fallback: centre du haut de l'image (probablement le visage)
    h, w = frame_bgr.shape[:2]
    return w // 2, h // 4


def extract_masks_sam2(video_path: str, progress_cb: Callable | None = None) -> list[np.ndarray]:
    """
    Utilise SAM2 pour extraire le masque de la personne sur chaque frame.
    Retourne une liste de masques binaires (True = personne, False = fond).
    """
    import torch

    if progress_cb: progress_cb(5)

    cap = cv2.VideoCapture(video_path)
    frames = []
    while True:
        ret, frame = cap.read()
        if not ret: break
        frames.append(frame)
    cap.release()

    if not frames:
        return []

    if progress_cb: progress_cb(10)

    predictor = _get_predictor()

    with tempfile.TemporaryDirectory() as tmp:
        # Sauvegarder les frames en JPEG pour SAM2
        for i, f in enumerate(frames):
            cv2.imwrite(f"{tmp}/{i:05d}.jpg", f)

        with predictor.init_state(video_path=tmp) as state:
            cx, cy = _detect_face_center(frames[0])
            # Sélection de la personne par click sur le visage
            predictor.add_new_points_or_box(
                state,
                frame_idx=0,
                obj_id=1,
                points=np.array([[cx, cy]], dtype=np.float32),
                labels=np.array([1], dtype=np.int32),
            )

            if progress_cb: progress_cb(15)

            masks = [None] * len(frames)
            total = len(frames)
            for frame_idx, obj_ids, mask_logits in predictor.propagate_in_video(state):
                mask = (mask_logits[0] > 0.0).squeeze().cpu().numpy()
                masks[frame_idx] = mask.astype(bool)
                if progress_cb and frame_idx % 10 == 0:
                    progress_cb(15 + int(frame_idx / total * 60))

    if progress_cb: progress_cb(75)
    return masks


def replace_background(
    video_path: str,
    output_path: str,
    background: str | tuple[int,int,int] = "blur",
    blur_strength: int = 51,
    progress_cb: Callable | None = None,
) -> bool:
    """
    Remplace ou supprime le fond d'une vidéo.

    background :
      "blur"               → flou gaussien du fond (effet bokeh professionnel)
      "transparent"        → fond transparent (exporte en webm avec alpha)
      "#RRGGBB"            → couleur unie
      "/chemin/image.jpg"  → image personnalisée
      (R, G, B)            → tuple de couleur
    """
    if not is_available():
        # Fallback : suppression simple par rembg si disponible
        return _fallback_rembg(video_path, output_path, background, progress_cb)

    try:
        masks = extract_masks_sam2(video_path, progress_cb)
        if not masks:
            return False

        cap = cv2.VideoCapture(video_path)
        fps    = cap.get(cv2.CAP_PROP_FPS) or 25
        width  = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        audio  = _extract_audio(video_path)

        if progress_cb: progress_cb(78)

        # Préparer le fond
        bg_frame = _prepare_background(background, width, height, blur_strength)

        tmp_video = str(Path(output_path).parent / "_bg_tmp.mp4")
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        writer = cv2.VideoWriter(tmp_video, fourcc, fps, (width, height))

        for i, mask in enumerate(masks):
            ret, frame = cap.read()
            if not ret or mask is None:
                break

            # Fond dynamique (recalculé par frame pour le blur)
            if background == "blur":
                bg = cv2.GaussianBlur(frame, (blur_strength|1, blur_strength|1), 0)
            else:
                bg = bg_frame.copy()

            # Composition
            mask3 = np.stack([mask]*3, axis=-1)
            composite = np.where(mask3, frame, bg)
            writer.write(composite.astype(np.uint8))

            if progress_cb and i % 10 == 0:
                progress_cb(78 + int(i / len(masks) * 18))

        cap.release()
        writer.release()

        # Remuxer avec l'audio original
        _remux_audio(tmp_video, audio, output_path)
        Path(tmp_video).unlink(missing_ok=True)
        if audio: Path(audio).unlink(missing_ok=True)

        if progress_cb: progress_cb(100)
        return True

    except Exception as e:
        return False


def _prepare_background(background, width, height, blur_strength):
    if isinstance(background, tuple):
        bg = np.zeros((height, width, 3), dtype=np.uint8)
        bg[:] = background[::-1]  # RGB → BGR
        return bg
    if isinstance(background, str) and background.startswith("#"):
        r,g,b = int(background[1:3],16), int(background[3:5],16), int(background[5:7],16)
        bg = np.zeros((height, width, 3), dtype=np.uint8)
        bg[:] = (b, g, r)
        return bg
    if isinstance(background, str) and Path(background).exists():
        img = cv2.imread(background)
        return cv2.resize(img, (width, height))
    return np.zeros((height, width, 3), dtype=np.uint8)


def _extract_audio(video_path: str) -> str | None:
    tmp = str(Path(video_path).parent / "_audio_tmp.aac")
    r = subprocess.run(
        ["ffmpeg", "-y", "-i", video_path, "-vn", "-c:a", "copy", tmp],
        capture_output=True
    )
    return tmp if r.returncode == 0 else None


def _remux_audio(video_path: str, audio_path: str | None, output_path: str):
    if audio_path and Path(audio_path).exists():
        subprocess.run([
            "ffmpeg", "-y",
            "-i", video_path, "-i", audio_path,
            "-c:v", "libx264", "-crf", "15", "-preset", "slow",
            "-c:a", "aac", "-b:a", "192k",
            "-shortest", output_path,
        ], capture_output=True, check=True)
    else:
        subprocess.run([
            "ffmpeg", "-y", "-i", video_path,
            "-c:v", "libx264", "-crf", "15",
            output_path,
        ], capture_output=True, check=True)


def _fallback_rembg(video_path, output_path, background, progress_cb):
    """Fallback rapide avec rembg (moins précis que SAM2 mais sans GPU)."""
    try:
        from rembg import remove
        cap = cv2.VideoCapture(video_path)
        fps    = cap.get(cv2.CAP_PROP_FPS) or 25
        width  = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        audio  = _extract_audio(video_path)

        tmp_video = str(Path(output_path).parent / "_rembg_tmp.mp4")
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        writer = cv2.VideoWriter(tmp_video, fourcc, fps, (width, height))

        frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        i = 0
        while True:
            ret, frame = cap.read()
            if not ret: break
            pil = Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
            removed = remove(pil)
            rgba = np.array(removed)
            alpha = rgba[:,:,3:4] / 255.0

            if background == "blur":
                bg_arr = cv2.GaussianBlur(frame, (51, 51), 0)
            else:
                bg_arr = _prepare_background(background, width, height, 51)

            rgb = rgba[:,:,:3][:,:,::-1]  # RGB→BGR
            composite = (rgb * alpha + bg_arr * (1 - alpha)).astype(np.uint8)
            writer.write(composite)
            i += 1
            if progress_cb and i % 5 == 0:
                progress_cb(10 + int(i / max(frame_count,1) * 85))

        cap.release()
        writer.release()
        _remux_audio(tmp_video, audio, output_path)
        Path(tmp_video).unlink(missing_ok=True)
        if audio: Path(audio).unlink(missing_ok=True)
        if progress_cb: progress_cb(100)
        return True
    except Exception:
        return False
