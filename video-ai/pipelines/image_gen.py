"""
FLUX.1 image generation — best open-source image model.
Used to generate base images before animating with Wan2.1,
or as standalone high-quality image generation.
"""

from __future__ import annotations

import threading
from typing import Callable

import torch
from PIL import Image

from .gpu_utils import free_vram, get_dtype, get_device, get_vram_gb, select_flux_model
from .prompt_engine import build_flux_prompt

_lock = threading.Lock()
_loaded_model_id: str | None = None
_pipe = None


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

    if "FLUX" in model_id or "flux" in model_id.lower():
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
    is_flux = "FLUX" in model_id or "flux" in model_id.lower()
    is_schnell = "schnell" in model_id

    if is_flux:
        steps = 4 if is_schnell else 50
        guidance = 0.0 if is_schnell else 3.5
    else:
        steps = 30
        guidance = 7.5

    # Max resolution based on VRAM
    if vram < 12:
        width = min(width, 1024)
        height = min(height, 1024)

    return {
        "num_inference_steps": steps,
        "guidance_scale": guidance,
        "width": width,
        "height": height,
    }


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
    Generate a high-quality image with FLUX.1.
    Automatically applies Real-ESRGAN + GFPGAN post-processing.
    Returns the output path.
    """
    vram = get_vram_gb()
    model_id = select_flux_model(vram)
    positive, _ = build_flux_prompt(prompt, style)
    params = _get_optimal_params(model_id, vram, width, height)
    is_flux = "FLUX" in model_id or "flux" in model_id.lower()

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
