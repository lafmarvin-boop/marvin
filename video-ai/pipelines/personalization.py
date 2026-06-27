"""
DreamBooth LoRA personalisation — l'amélioration la plus impactante.

Entraîne un mini-modèle sur 10-20 photos d'une personne spécifique.
Après ~2-3h de training (RTX 3080) ou ~45min (RTX 4090), le modèle
connaît parfaitement ce visage → résultat quasi-parfait et consistant.

C'est exactement ce que HeyGen fait pour les "avatars personnalisés".

Workflow :
  1. train_person_lora(photos_dir, name)   → entraîne + sauve le LoRA
  2. list_trained_persons()                → liste les LoRA disponibles
  3. Le pipeline image_gen.py charge auto le bon LoRA si dispo
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Callable

BASE_DIR  = Path(__file__).parent.parent
LORAS_DIR = BASE_DIR / "loras"
TRAIN_SCRIPT = BASE_DIR / "train_dreambooth_lora_flux.py"

LORAS_DIR.mkdir(exist_ok=True)


# ─── Meta-données des LoRA entraînés ──────────────────────────────────────────

def _meta_path(name: str) -> Path:
    return LORAS_DIR / name / "meta.json"


def save_meta(name: str, token: str, steps: int, photos: int):
    d = LORAS_DIR / name
    d.mkdir(exist_ok=True)
    _meta_path(name).write_text(json.dumps({
        "name": name, "token": token, "steps": steps,
        "photos": photos, "version": "flux-lora-v1"
    }, ensure_ascii=False))


def list_trained_persons() -> list[dict]:
    result = []
    for p in LORAS_DIR.iterdir():
        meta = p / "meta.json"
        if meta.exists():
            result.append(json.loads(meta.read_text()))
    return result


def get_lora_path(name: str) -> Path | None:
    p = LORAS_DIR / name / "pytorch_lora_weights.safetensors"
    return p if p.exists() else None


def is_trained(name: str) -> bool:
    return get_lora_path(name) is not None


# ─── Preprocessing des photos ─────────────────────────────────────────────────

def preprocess_photos(photos_dir: str, output_dir: str, target_size: int = 512) -> int:
    """
    Prépare les photos pour l'entraînement :
    - Détecte et recadre le visage
    - Redimensionne à target_size x target_size
    - Convertit en PNG
    Retourne le nombre de photos traitées.
    """
    import cv2
    from PIL import Image

    src = Path(photos_dir)
    dst = Path(output_dir)
    dst.mkdir(parents=True, exist_ok=True)

    exts = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
    photos = [f for f in src.iterdir() if f.suffix.lower() in exts]

    try:
        from .face_consistency import _get_face_app
        face_app = _get_face_app()
    except Exception:
        face_app = None

    count = 0
    for photo in photos:
        try:
            img_cv = cv2.imread(str(photo))
            if img_cv is None:
                continue

            if face_app is not None:
                faces = face_app.get(img_cv)
                if faces:
                    face = sorted(
                        faces,
                        key=lambda f: (f.bbox[2]-f.bbox[0]) * (f.bbox[3]-f.bbox[1])
                    )[-1]
                    x1, y1, x2, y2 = [int(v) for v in face.bbox]
                    h, w = img_cv.shape[:2]
                    cx, cy = (x1+x2)//2, (y1+y2)//2
                    half = int(max(x2-x1, y2-y1) * 0.8)
                    x1 = max(0, cx-half); y1 = max(0, cy-half)
                    x2 = min(w, cx+half); y2 = min(h, cy+half)
                    img_cv = img_cv[y1:y2, x1:x2]

            img_pil = Image.fromarray(cv2.cvtColor(img_cv, cv2.COLOR_BGR2RGB))
            img_pil = img_pil.resize((target_size, target_size), Image.LANCZOS)
            img_pil.save(dst / f"{count:04d}.png")
            count += 1
        except Exception:
            continue

    return count


# ─── Entraînement LoRA ────────────────────────────────────────────────────────

def train_person_lora(
    photos_dir: str,
    person_name: str,
    steps: int = 500,
    learning_rate: float = 1e-4,
    progress_cb: Callable[[int, str], None] | None = None,
) -> str:
    """
    Entraîne un LoRA FLUX.1 sur les photos d'une personne.

    photos_dir  : dossier avec 10-20 photos
    person_name : identifiant unique (ex: "marie", "jean_dupont")
    steps       : nombre de steps (500 = ~45min RTX4090, ~2h RTX3080)
    Retourne le chemin du LoRA entraîné.
    """
    if not TRAIN_SCRIPT.exists():
        raise RuntimeError(
            "Script d'entraînement manquant. Lance install.sh d'abord."
        )

    # Token unique pour cette personne
    token = f"sks{person_name.lower().replace(' ', '')}person"

    # Préparer les photos
    if progress_cb: progress_cb(2, "Préparation des photos…")
    processed_dir = LORAS_DIR / person_name / "processed"
    n_photos = preprocess_photos(photos_dir, str(processed_dir))
    if n_photos < 3:
        raise ValueError(f"Pas assez de photos traitées ({n_photos}). Minimum 3 requis.")

    if progress_cb: progress_cb(8, f"{n_photos} photos prêtes. Lancement de l'entraînement…")

    output_dir = LORAS_DIR / person_name

    from .gpu_utils import get_vram_gb
    vram = get_vram_gb()

    # Paramètres adaptés au GPU
    batch_size = 1
    grad_accum = 4 if vram < 16 else 2
    rank = 16 if vram >= 16 else 8

    cmd = [
        sys.executable, str(TRAIN_SCRIPT),
        "--pretrained_model_name_or_path", "black-forest-labs/FLUX.1-dev",
        "--instance_data_dir",             str(processed_dir),
        "--output_dir",                    str(output_dir),
        "--instance_prompt",               f"a photo of {token}",
        "--resolution",                    "512",
        "--train_batch_size",              str(batch_size),
        "--gradient_accumulation_steps",   str(grad_accum),
        "--max_train_steps",               str(steps),
        "--learning_rate",                 str(learning_rate),
        "--lr_scheduler",                  "constant",
        "--lr_warmup_steps",               "0",
        "--rank",                          str(rank),
        "--mixed_precision",               "bf16",
        "--gradient_checkpointing",
        "--report_to",                     "none",
        "--seed",                          "42",
    ]

    if vram >= 12:
        cmd.append("--use_8bit_adam")

    if progress_cb: progress_cb(10, "Entraînement en cours…")

    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        cwd=str(BASE_DIR),
    )

    current_step = 0
    for line in proc.stdout:
        line = line.strip()
        if "step" in line.lower() and "/" in line:
            try:
                parts = [p for p in line.split() if "/" in p]
                if parts:
                    cur, tot = parts[0].split("/")
                    current_step = int(cur)
                    pct = int(10 + (int(cur) / int(tot)) * 85)
                    if progress_cb:
                        progress_cb(pct, f"Training step {cur}/{tot}…")
            except Exception:
                pass

    proc.wait()
    if proc.returncode != 0:
        raise RuntimeError("Entraînement LoRA échoué.")

    # Sauvegarder les métadonnées
    save_meta(person_name, token, steps, n_photos)

    if progress_cb: progress_cb(100, f"LoRA '{person_name}' prêt !")
    return str(output_dir / "pytorch_lora_weights.safetensors")


# ─── Inférence avec LoRA ──────────────────────────────────────────────────────

def generate_with_lora(
    person_name: str,
    prompt: str,
    output_path: str,
    width: int = 1024,
    height: int = 1024,
    steps: int = 28,
    lora_scale: float = 0.9,
    progress_cb: Callable[[int], None] | None = None,
) -> bool:
    """
    Génère une image en utilisant le LoRA entraîné pour cette personne.
    Retourne True si succès, False si LoRA non disponible.
    """
    lora_path = get_lora_path(person_name)
    if not lora_path:
        return False

    meta_file = _meta_path(person_name)
    if not meta_file.exists():
        return False
    meta = json.loads(meta_file.read_text())
    token = meta["token"]

    # Injecter le token dans le prompt
    if token not in prompt:
        prompt = f"a photo of {token}, {prompt}"

    from .gpu_utils import free_vram, get_dtype, get_device, get_vram_gb
    import torch

    if progress_cb: progress_cb(5)

    dtype = get_dtype()
    device = get_device()
    vram = get_vram_gb()

    from diffusers import FluxPipeline

    pipe = FluxPipeline.from_pretrained(
        "black-forest-labs/FLUX.1-dev",
        torch_dtype=dtype,
    )
    pipe.load_lora_weights(str(lora_path))
    pipe.fuse_lora(lora_scale=lora_scale)

    if vram < 16:
        pipe.enable_model_cpu_offload()
    else:
        pipe.to(device)

    if progress_cb: progress_cb(20)

    from .prompt_engine import get_negative_prompt
    result = pipe(
        prompt=prompt,
        width=width,
        height=height,
        num_inference_steps=steps,
        guidance_scale=3.5,
        joint_attention_kwargs={"scale": lora_scale},
    )

    if progress_cb: progress_cb(85)
    result.images[0].save(output_path)

    del pipe
    free_vram()

    if progress_cb: progress_cb(100)
    return True
