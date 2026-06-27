"""
Face consistency pipeline using IP-Adapter FaceID.
Ensures the face from a reference photo is preserved in every generated image,
then Wan2.1 animates the result.

Without this: Wan2.1 may gradually drift from the original face.
With this:    The face identity stays consistent across all frames.
"""

from __future__ import annotations

import threading
from pathlib import Path
from typing import Callable

import numpy as np
from PIL import Image

from .gpu_utils import free_vram, get_dtype, get_device, get_vram_gb

BASE_DIR = Path(__file__).parent.parent
MODELS_DIR = BASE_DIR / "models"

_lock = threading.Lock()
_pipe = None
_face_app = None


def _get_face_app():
    """Load InsightFace for face embedding extraction."""
    global _face_app
    if _face_app is None:
        try:
            import insightface
            from insightface.app import FaceAnalysis
            _face_app = FaceAnalysis(name="buffalo_l", providers=["CUDAExecutionProvider", "CPUExecutionProvider"])
            _face_app.prepare(ctx_id=0 if get_device() == "cuda" else -1, det_size=(640, 640))
        except ImportError:
            return None
    return _face_app


def _load_pipe():
    """Load IP-Adapter FaceID pipeline on top of SDXL."""
    global _pipe
    if _pipe is not None:
        return _pipe

    free_vram()
    dtype = get_dtype()
    device = get_device()
    vram = get_vram_gb()

    try:
        from diffusers import StableDiffusionXLPipeline
        from ip_adapter.ip_adapter_faceid import IPAdapterFaceIDXL

        base_model = "stabilityai/stable-diffusion-xl-base-1.0"
        pipe = StableDiffusionXLPipeline.from_pretrained(
            base_model, torch_dtype=dtype, use_safetensors=True
        )

        ip_ckpt = MODELS_DIR / "ip-adapter-faceid_sdxl.bin"
        ip_model = IPAdapterFaceIDXL(pipe, str(ip_ckpt), device)

        if vram < 16:
            pipe.enable_model_cpu_offload()

        _pipe = ip_model
        return _pipe

    except (ImportError, Exception) as e:
        return None


def extract_face_embedding(image_path: str) -> np.ndarray | None:
    """Extract face identity embedding from a portrait photo."""
    try:
        import cv2
        face_app = _get_face_app()
        if face_app is None:
            return None

        img = cv2.imread(image_path)
        if img is None:
            return None

        faces = face_app.get(img)
        if not faces:
            return None

        # Use the largest face
        face = sorted(faces, key=lambda f: (f.bbox[2]-f.bbox[0]) * (f.bbox[3]-f.bbox[1]))[-1]
        return face.normed_embedding

    except Exception:
        return None


def generate_with_face(
    face_image_path: str,
    prompt: str,
    output_path: str,
    width: int = 1024,
    height: int = 1024,
    steps: int = 30,
    scale: float = 0.8,
    progress_cb: Callable[[int], None] | None = None,
) -> bool:
    """
    Generate an image that preserves the face from face_image_path.
    Returns True if IP-Adapter was used, False if fell back to standard FLUX.
    """
    if progress_cb: progress_cb(5)

    with _lock:
        # Extract face embedding
        embedding = extract_face_embedding(face_image_path)
        if embedding is None:
            return False

        # Load pipeline
        ip_model = _load_pipe()
        if ip_model is None:
            return False

        if progress_cb: progress_cb(20)

        from .prompt_engine import build_flux_prompt
        positive, negative = build_flux_prompt(prompt, "realistic")

        images = ip_model.generate(
            prompt=positive,
            negative_prompt=negative,
            faceid_embeds=embedding[None],
            scale=scale,
            width=width,
            height=height,
            num_inference_steps=steps,
            guidance_scale=7.5,
        )

        if progress_cb: progress_cb(90)
        images[0].save(output_path)

    if progress_cb: progress_cb(100)
    return True


def crop_face_for_portrait(image_path: str, output_path: str, expand: float = 1.4) -> bool:
    """
    Detect and crop the face from an image for optimal talking head results.
    Hallo2 and EchoMimic work much better with a clean face crop.
    """
    try:
        import cv2
        face_app = _get_face_app()
        if face_app is None:
            return False

        img = cv2.imread(image_path)
        h, w = img.shape[:2]
        faces = face_app.get(img)
        if not faces:
            return False

        face = sorted(faces, key=lambda f: (f.bbox[2]-f.bbox[0]) * (f.bbox[3]-f.bbox[1]))[-1]
        x1, y1, x2, y2 = [int(v) for v in face.bbox]

        # Expand the crop around the face
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        size = max(x2 - x1, y2 - y1)
        half = int(size * expand / 2)

        x1 = max(0, cx - half)
        y1 = max(0, cy - half)
        x2 = min(w, cx + half)
        y2 = min(h, cy + half)

        cropped = img[y1:y2, x1:x2]
        cropped = cv2.resize(cropped, (512, 512), interpolation=cv2.INTER_LANCZOS4)
        cv2.imwrite(output_path, cropped)
        return True

    except Exception:
        return False
