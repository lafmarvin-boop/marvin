"""
FLUX.1 image generation — best open-source image model.
Used to generate a base image before animating with Wan2.1,
or to create standalone high-quality images.
"""

from __future__ import annotations

import threading
from typing import Callable

import torch
from PIL import Image

from .gpu_utils import free_vram, get_dtype, get_device, get_vram_gb, select_flux_model

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
        pipe = StableDiffusionXLPipeline.from_pretrained(model_id, torch_dtype=dtype)

    if device == "cuda":
        if vram < 16:
            pipe.enable_model_cpu_offload()
        else:
            pipe.to(device)
    else:
        pipe.to("cpu")

    _pipe = pipe
    _loaded_model_id = model_id
    return pipe


def generate_image(
    prompt: str,
    output_path: str,
    width: int = 1024,
    height: int = 1024,
    steps: int | None = None,
    guidance: float = 3.5,
    progress_cb: Callable[[int], None] | None = None,
    models_dir: str = "./models",
) -> str:
    """
    Generate a high-quality image with FLUX.1.
    Returns the output path.
    """
    vram = get_vram_gb()
    model_id = select_flux_model(vram)

    # FLUX.1-schnell needs fewer steps; dev needs more
    if steps is None:
        steps = 4 if "schnell" in model_id else 28

    with _lock:
        if progress_cb:
            progress_cb(10)

        pipe = _load_pipe(model_id)

        if progress_cb:
            progress_cb(30)

        is_flux = "FLUX" in model_id or "flux" in model_id.lower()

        if is_flux:
            result = pipe(
                prompt=prompt,
                width=width,
                height=height,
                num_inference_steps=steps,
                guidance_scale=guidance,
            )
        else:
            result = pipe(
                prompt=prompt,
                width=width,
                height=height,
                num_inference_steps=steps,
            )

        if progress_cb:
            progress_cb(90)

        image = result.images[0]
        image.save(output_path)

        if progress_cb:
            progress_cb(100)

    return output_path
