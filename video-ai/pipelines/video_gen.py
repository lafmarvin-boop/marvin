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
from .prompt_engine import build_wan_prompt

_lock = threading.Lock()
_loaded_model_id: str | None = None
_pipe = None


def _load_pipe(model_id: str, mode: str):
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
            pipe.enable_vae_slicing()
            pipe.enable_vae_tiling()
        else:
            pipe.to(device)
    else:
        pipe.to("cpu")

    _pipe = pipe
    _loaded_model_id = model_id
    return pipe


def _get_optimal_params(vram: float, num_seconds: int) -> dict:
    """Return best inference params based on available VRAM."""
    num_frames = num_seconds * 16 + 1

    if vram >= 16:
        return {
            "num_frames": num_frames,
            "num_inference_steps": 50,
            "guidance_scale": 6.0,
            "height": 720,
            "width": 1280,
        }
    elif vram >= 12:
        return {
            "num_frames": num_frames,
            "num_inference_steps": 40,
            "guidance_scale": 5.5,
            "height": 480,
            "width": 832,
        }
    else:
        return {
            "num_frames": min(num_frames, 49),  # limit for small VRAM
            "num_inference_steps": 30,
            "guidance_scale": 5.0,
            "height": 480,
            "width": 832,
        }


def generate_from_image(
    image_path: str,
    prompt: str,
    output_path: str,
    num_seconds: int = 4,
    enhance: bool = True,
    color_preset: str = "cinema",
    progress_cb: Callable[[int], None] | None = None,
    models_dir: str = "./models",
):
    """
    Animate a photo using Wan2.1 I2V + full post-processing pipeline.
    The face/scene in the image is preserved and animated realistically.
    """
    vram = get_vram_gb()
    model_id = select_wan_model("i2v", vram)
    params = _get_optimal_params(vram, num_seconds)
    positive, negative = build_wan_prompt(prompt)

    with _lock:
        if progress_cb:
            progress_cb(5)

        pipe = _load_pipe(model_id, "i2v")

        if progress_cb:
            progress_cb(15)

        image = Image.open(image_path).convert("RGB")
        image = image.resize((params["width"], params["height"]), Image.LANCZOS)

        from diffusers.utils import export_to_video

        if vram >= 12:
            result = pipe(
                image=image,
                prompt=positive,
                negative_prompt=negative,
                num_frames=params["num_frames"],
                guidance_scale=params["guidance_scale"],
                num_inference_steps=params["num_inference_steps"],
            )
        else:
            result = pipe(
                prompt=positive,
                negative_prompt=negative,
                num_frames=params["num_frames"],
                height=params["height"],
                width=params["width"],
                guidance_scale=params["guidance_scale"],
                num_inference_steps=params["num_inference_steps"],
            )

        if progress_cb:
            progress_cb(70)

        raw_path = output_path.replace(".mp4", "_raw.mp4")
        export_to_video(result.frames[0], raw_path, fps=16)

    # Post-processing (outside GPU lock)
    if enhance:
        if progress_cb:
            progress_cb(75)
        from .enhancer import enhance_video
        enhance_video(
            raw_path, output_path,
            upscale=True,
            face_enhance=True,
            interpolate=True,
            target_fps=60,
            color_preset=color_preset,
            progress_cb=lambda p: progress_cb(75 + int(p * 0.24)) if progress_cb else None,
        )
        Path(raw_path).unlink(missing_ok=True)
    else:
        import shutil
        shutil.move(raw_path, output_path)

    if progress_cb:
        progress_cb(100)


def generate_from_text(
    prompt: str,
    output_path: str,
    num_seconds: int = 4,
    enhance: bool = True,
    color_preset: str = "cinema",
    progress_cb: Callable[[int], None] | None = None,
    models_dir: str = "./models",
):
    """Generate a video purely from a text prompt using Wan2.1 T2V."""
    vram = get_vram_gb()
    model_id = select_wan_model("t2v", vram)
    params = _get_optimal_params(vram, num_seconds)
    positive, negative = build_wan_prompt(prompt)

    with _lock:
        if progress_cb:
            progress_cb(5)

        pipe = _load_pipe(model_id, "t2v")

        if progress_cb:
            progress_cb(15)

        from diffusers.utils import export_to_video

        result = pipe(
            prompt=positive,
            negative_prompt=negative,
            num_frames=params["num_frames"],
            height=params["height"],
            width=params["width"],
            guidance_scale=params["guidance_scale"],
            num_inference_steps=params["num_inference_steps"],
        )

        if progress_cb:
            progress_cb(70)

        raw_path = output_path.replace(".mp4", "_raw.mp4")
        export_to_video(result.frames[0], raw_path, fps=16)

    if enhance:
        if progress_cb:
            progress_cb(75)
        from .enhancer import enhance_video
        enhance_video(
            raw_path, output_path,
            upscale=True,
            face_enhance=False,
            interpolate=True,
            target_fps=60,
            color_preset=color_preset,
            progress_cb=lambda p: progress_cb(75 + int(p * 0.24)) if progress_cb else None,
        )
        Path(raw_path).unlink(missing_ok=True)
    else:
        import shutil
        shutil.move(raw_path, output_path)

    if progress_cb:
        progress_cb(100)
