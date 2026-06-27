"""GPU detection and resource management."""

import subprocess
import torch


def get_vram_gb() -> float:
    if not torch.cuda.is_available():
        return 0.0
    return torch.cuda.get_device_properties(0).total_memory / 1e9


def get_gpu_name() -> str:
    if not torch.cuda.is_available():
        return "CPU only"
    return torch.cuda.get_device_properties(0).name


def get_device():
    return "cuda" if torch.cuda.is_available() else "cpu"


def get_dtype():
    import torch
    return torch.bfloat16 if torch.cuda.is_available() else torch.float32


def free_vram():
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
        torch.cuda.ipc_collect()


def get_system_info() -> dict:
    vram = get_vram_gb()
    name = get_gpu_name()

    if vram == 0:
        tier = "cpu"
        quality = "Lent (CPU), qualité basique"
    elif vram < 8:
        tier = "low"
        quality = "Basse qualité — GPU trop limité pour la vidéo IA"
    elif vram < 12:
        tier = "medium"
        quality = "Qualité correcte — Wan2.1 1.3B"
    elif vram < 16:
        tier = "high"
        quality = "Bonne qualité — Wan2.1 14B 480p + FLUX Schnell"
    else:
        tier = "ultra"
        quality = "Qualité maximale — Wan2.1 14B 720p + FLUX Dev"

    return {
        "gpu": name,
        "vram_gb": round(vram, 1),
        "tier": tier,
        "quality_label": quality,
        "cuda": torch.cuda.is_available(),
    }


def select_wan_model(mode: str, vram: float) -> str:
    """Pick the best Wan2.1 model for available VRAM."""
    if mode == "i2v":
        if vram >= 16:
            return "Wan-AI/Wan2.1-I2V-14B-720P"
        elif vram >= 12:
            return "Wan-AI/Wan2.1-I2V-14B-480P"
        else:
            return "Wan-AI/Wan2.1-T2V-1.3B"  # no I2V at 1.3B, fall back to T2V
    else:  # t2v
        if vram >= 16:
            return "Wan-AI/Wan2.1-T2V-14B"
        else:
            return "Wan-AI/Wan2.1-T2V-1.3B"


def select_flux_model(vram: float) -> str:
    if vram >= 16:
        return "black-forest-labs/FLUX.1-dev"
    elif vram >= 10:
        return "black-forest-labs/FLUX.1-schnell"
    else:
        return "stabilityai/stable-diffusion-xl-base-1.0"
