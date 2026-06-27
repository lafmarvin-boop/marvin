"""
Wan2.1 video generation — image-to-video and text-to-video.
Best open-source equivalent to Kling AI / Runway Gen-3.
"""

from __future__ import annotations

import threading
from pathlib import Path
from typing import Callable

import torch
from PIL import Image

from .gpu_utils import free_vram, get_dtype, get_device, get_vram_gb, select_wan_model

_lock = threading.Lock()
_loaded_model_id: str | None = None
_pipe = None


def _load_pipe(model_id: str, mode: str):
    global _pipe, _loaded_model_id

    if _loaded_model_id == model_id:
        return _pipe

    # Unload previous model
    if _pipe is not None:
        del _pipe
        _pipe = None
        free_vram()

    dtype = get_dtype()
    device = get_device()
    vram = get_vram_gb()

    if mode == "i2v" and vram >= 12:
        from diffusers import WanImageToVideoPipeline
        pipe = WanImageToVideoPipeline.from_pretrained(
            model_id,
            torch_dtype=dtype,
        )
    else:
        from diffusers import WanPipeline
        pipe = WanPipeline.from_pretrained(
            model_id,
            torch_dtype=dtype,
        )

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


def generate_from_image(
    image_path: str,
    prompt: str,
    output_path: str,
    num_seconds: int = 4,
    negative_prompt: str = "blurry, low quality, distorted, deformed, ugly, artifacts",
    progress_cb: Callable[[int], None] | None = None,
    models_dir: str = "./models",
):
    """
    Animate a person/scene from a single photo using Wan2.1 I2V.
    The face and scene in the image are preserved throughout the video.
    """
    vram = get_vram_gb()
    model_id = select_wan_model("i2v", vram)
    num_frames = num_seconds * 16 + 1  # Wan2.1 uses 16fps, needs odd frame count

    with _lock:
        if progress_cb:
            progress_cb(10)

        pipe = _load_pipe(model_id, "i2v")

        if progress_cb:
            progress_cb(25)

        image = Image.open(image_path).convert("RGB")

        # Resize to model-compatible resolution
        if vram >= 16:
            target_w, target_h = 1280, 720
        else:
            target_w, target_h = 832, 480

        image = image.resize((target_w, target_h), Image.LANCZOS)

        from diffusers.utils import export_to_video

        if progress_cb:
            progress_cb(35)

        if vram >= 12:
            # True image-to-video
            result = pipe(
                image=image,
                prompt=prompt,
                negative_prompt=negative_prompt,
                num_frames=num_frames,
                guidance_scale=5.0,
                num_inference_steps=30,
            )
        else:
            # Fallback: text-to-video (1.3B model, no I2V)
            result = pipe(
                prompt=prompt,
                negative_prompt=negative_prompt,
                num_frames=num_frames,
                height=480,
                width=832,
                guidance_scale=6.0,
                num_inference_steps=25,
            )

        if progress_cb:
            progress_cb(85)

        export_to_video(result.frames[0], output_path, fps=16)

        if progress_cb:
            progress_cb(100)


def generate_from_text(
    prompt: str,
    output_path: str,
    num_seconds: int = 4,
    negative_prompt: str = "blurry, low quality, distorted, deformed, ugly",
    progress_cb: Callable[[int], None] | None = None,
    models_dir: str = "./models",
):
    """Generate a video purely from a text prompt using Wan2.1 T2V."""
    vram = get_vram_gb()
    model_id = select_wan_model("t2v", vram)
    num_frames = num_seconds * 16 + 1

    with _lock:
        if progress_cb:
            progress_cb(10)

        pipe = _load_pipe(model_id, "t2v")

        if progress_cb:
            progress_cb(30)

        from diffusers.utils import export_to_video

        if vram >= 16:
            h, w = 720, 1280
        else:
            h, w = 480, 832

        result = pipe(
            prompt=prompt,
            negative_prompt=negative_prompt,
            num_frames=num_frames,
            height=h,
            width=w,
            guidance_scale=6.0,
            num_inference_steps=30,
        )

        if progress_cb:
            progress_cb(85)

        export_to_video(result.frames[0], output_path, fps=16)

        if progress_cb:
            progress_cb(100)
