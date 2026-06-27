"""
Image generation — FLUX.1 by default, or any local SDXL .safetensors.

Drop a .safetensors file in video-ai/models/ and it will be detected
automatically and used instead of the default FLUX/SDXL HF model.
Local models have no content restrictions.
"""

from __future__ import annotations

import threading
from pathlib import Path
from typing import Callable

import torch
from PIL import Image

from .gpu_utils import free_vram, get_dtype, get_device, get_vram_gb, select_flux_model
from .prompt_engine import build_flux_prompt

BASE_DIR = Path(__file__).parent.parent
MODELS_DIR = BASE_DIR / "models"

_lock = threading.Lock()
_loaded_model_id: str | None = None
_pipe = None


def _find_local_model() -> Path | None:
    """
    Returns the first .safetensors found in models/ that looks like an SDXL
    checkpoint (>2 GB, not an IP-Adapter or LoRA shard).
    Priority: files named in LOCAL_MODEL env var > largest file found.
    """
    import os
    env_name = os.environ.get("LOCAL_MODEL", "")
    if env_name:
        p = MODELS_DIR / env_name
        if p.exists():
            return p

    candidates = [
        f for f in MODELS_DIR.glob("*.safetensors")
        if f.stat().st_size > 2 * 1024 ** 3  # >2 GB → full checkpoint
        and "ip-adapter" not in f.name.lower()
        and "lora" not in f.name.lower()
    ]
    if not candidates:
        return None
    return max(candidates, key=lambda f: f.stat().st_size)


def _load_pipe(model_id: str):
    global _pipe, _loaded_model_id

    if _loaded_model_id == model_id:
        return _pipe

    if _pipe is not None:
        del _pipe
        _pipe = None
        free_vram()

    dtype = get_dtype()
    device = get_device()
    vram = get_vram_gb()

    local = _find_local_model()

    if local is not None:
        from diffusers import StableDiffusionXLPipeline
        pipe = StableDiffusionXLPipeline.from_single_file(
            str(local),
            torch_dtype=dtype,
            use_safetensors=True,
        )
    elif "FLUX" in model_id or "flux" in model_id.lower():
        from diffusers import FluxPipeline
        pipe = FluxPipeline.from_pretrained(model_id, torch_dtype=dtype)
    else:
        from diffusers import StableDiffusionXLPipeline
        pipe = StableDiffusionXLPipeline.from_pretrained(
            model_id, torch_dtype=dtype, use_safetensors=True
        )

    if device == "cuda":
        if vram < 16:
            pipe.enable_model_cpu_offload()
            pipe.enable_vae_slicing()
        else:
            pipe.to(device)
    else:
        pipe.to("cpu")

    _pipe = pipe
    _loaded_model_id = model_id
    return pipe


def _get_optimal_params(model_id: str, vram: float, width: int, height: int) -> dict:
    local = _find_local_model()
    is_flux = (local is None) and ("FLUX" in model_id or "flux" in model_id.lower())
    is_schnell = "schnell" in model_id

    if is_flux:
        steps = 4 if is_schnell else 50
        guidance = 0.0 if is_schnell else 3.5
    else:
        # SDXL local or HF
        steps = 30
        guidance = 7.0

    if vram < 12:
        width = min(width, 1024)
        height = min(height, 1024)

    return {
        "num_inference_steps": steps,
        "guidance_scale": guidance,
        "width": width,
        "height": height,
    }


def get_active_model_name() -> str:
    """Returns the display name of the model that will be used."""
    local = _find_local_model()
    if local:
        return local.name
    vram = get_vram_gb()
    return select_flux_model(vram)


def generate_image(
    prompt: str,
    output_path: str,
    width: int = 1024,
    height: int = 1024,
    style: str = "realistic",
    enhance_faces: bool = True,
    upscale: bool = True,
    progress_cb: Callable[[int], None] | None = None,
    models_dir: str = "./models",
) -> str:
    """
    Generate a high-quality image.
    Uses a local .safetensors if present in models/, otherwise FLUX.1/SDXL.
    Returns the output path.
    """
    vram = get_vram_gb()
    local = _find_local_model()
    model_id = "local" if local else select_flux_model(vram)
    positive, _ = build_flux_prompt(prompt, style)
    params = _get_optimal_params(model_id, vram, width, height)
    is_flux = (local is None) and ("FLUX" in model_id or "flux" in model_id.lower())

    with _lock:
        if progress_cb:
            progress_cb(5)

        pipe = _load_pipe(model_id)

        if progress_cb:
            progress_cb(20)

        if is_flux:
            result = pipe(
                prompt=positive,
                width=params["width"],
                height=params["height"],
                num_inference_steps=params["num_inference_steps"],
                guidance_scale=params["guidance_scale"],
            )
        else:
            result = pipe(
                prompt=positive,
                width=params["width"],
                height=params["height"],
                num_inference_steps=params["num_inference_steps"],
                guidance_scale=params["guidance_scale"],
            )

        if progress_cb:
            progress_cb(70)

        raw_path = output_path.replace(".png", "_raw.png").replace(".jpg", "_raw.jpg")
        result.images[0].save(raw_path)

    # Post-processing (outside GPU lock)
    from pathlib import Path
    import shutil
    current = raw_path

    if enhance_faces:
        if progress_cb:
            progress_cb(75)
        from .enhancer import enhance_faces as ef
        face_out = raw_path.replace("_raw.", "_face.")
        if ef(current, face_out):
            current = face_out

    if upscale:
        if progress_cb:
            progress_cb(85)
        from .enhancer import upscale_image
        up_out = raw_path.replace("_raw.", "_up.")
        if upscale_image(current, up_out, scale=2):
            current = up_out

    shutil.copy(current, output_path)

    # Cleanup temp files
    for tmp in [raw_path,
                raw_path.replace("_raw.", "_face."),
                raw_path.replace("_raw.", "_up.")]:
        Path(tmp).unlink(missing_ok=True)

    if progress_cb:
        progress_cb(100)

    return output_path
