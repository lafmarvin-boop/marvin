#!/usr/bin/env python3
"""
Marvin Video AI — Serveur principal
Modes : animate (Wan2.1 I2V), create (Wan2.1 T2V), presenter (TTS + LatentSync)

Lancer : uvicorn server:app --host 0.0.0.0 --port 8765
"""

import asyncio
import json
import shutil
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from fastapi import FastAPI, File, Form, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse

app = FastAPI(title="Marvin Video AI")

BASE_DIR = Path(__file__).parent
OUTPUT_DIR = BASE_DIR / "outputs"
UPLOAD_DIR = BASE_DIR / "uploads"
AVATARS_DIR = BASE_DIR / "avatars"

for d in (OUTPUT_DIR, UPLOAD_DIR, AVATARS_DIR):
    d.mkdir(exist_ok=True)

app.add_middleware(
    CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"]
)

executor = ThreadPoolExecutor(max_workers=1)  # 1 job GPU à la fois


# ─── Status helpers ───────────────────────────────────────────────────────────

def _status(job_dir: Path, step: str, pct: int, error: str = ""):
    job_dir.joinpath("status.json").write_text(
        json.dumps({"step": step, "pct": pct, "error": error})
    )


# ─── Routes ───────────────────────────────────────────────────────────────────

@app.get("/", response_class=HTMLResponse)
async def index():
    return HTMLResponse((BASE_DIR / "frontend" / "index.html").read_text())


@app.get("/api/system")
async def system_info():
    from pipelines.gpu_utils import get_system_info
    info = get_system_info()
    from pipelines.lipsync import is_available as lipsync_ok
    info["lipsync_available"] = lipsync_ok()
    return info


@app.get("/api/voices")
async def voices():
    from pipelines.tts import get_voices
    return {"voices": get_voices()}


@app.get("/api/avatars")
async def list_avatars():
    exts = {".jpg", ".jpeg", ".png", ".webp"}
    return {"avatars": [f.name for f in AVATARS_DIR.iterdir() if f.suffix.lower() in exts]}


@app.post("/api/upload-avatar")
async def upload_avatar(file: UploadFile = File(...)):
    ext = Path(file.filename).suffix.lower()
    if ext not in {".jpg", ".jpeg", ".png", ".webp"}:
        return JSONResponse({"error": "Format non supporté"}, status_code=400)
    name = f"{uuid.uuid4().hex[:8]}{ext}"
    (AVATARS_DIR / name).write_bytes(await file.read())
    return {"name": name}


@app.get("/api/avatar/{name}")
async def get_avatar(name: str):
    p = AVATARS_DIR / name
    if not p.exists():
        return JSONResponse({"error": "Introuvable"}, status_code=404)
    return FileResponse(str(p))


@app.post("/api/generate")
async def generate(
    mode: str = Form(...),          # "animate" | "create" | "presenter"
    prompt: str = Form(""),         # texte / action
    text: str = Form(""),           # texte à prononcer (mode presenter)
    voice: str = Form("fr-FR-DeniseNeural"),
    voice_rate: str = Form("+0%"),
    avatar_name: str = Form(""),    # fichier déjà uploadé
    avatar_file: UploadFile = File(None),
    duration: int = Form(4),        # secondes de vidéo
    quality: str = Form("auto"),    # "auto" | "fast" | "best"
):
    if mode not in ("animate", "create", "presenter"):
        return JSONResponse({"error": "Mode invalide"}, status_code=400)

    job_id = str(uuid.uuid4())[:8]
    job_dir = OUTPUT_DIR / job_id
    job_dir.mkdir(parents=True)
    _status(job_dir, "En attente…", 2)

    # Save uploaded avatar
    avatar_path = None
    if avatar_file and avatar_file.filename:
        ext = Path(avatar_file.filename).suffix
        avatar_path = job_dir / f"avatar{ext}"
        avatar_path.write_bytes(await avatar_file.read())
    elif avatar_name:
        src = AVATARS_DIR / avatar_name
        if src.exists():
            avatar_path = job_dir / src.name
            shutil.copy(src, avatar_path)

    params = {
        "mode": mode,
        "prompt": prompt,
        "text": text,
        "voice": voice,
        "voice_rate": voice_rate,
        "avatar_path": str(avatar_path) if avatar_path else None,
        "duration": duration,
        "quality": quality,
    }

    loop = asyncio.get_event_loop()
    loop.run_in_executor(executor, _run_job, job_id, job_dir, params)

    return {"job_id": job_id, "video_url": f"/api/video/{job_id}"}


def _run_job(job_id: str, job_dir: Path, params: dict):
    """Runs in thread pool — synchronous GPU work."""
    try:
        mode = params["mode"]

        if mode == "animate":
            _job_animate(job_dir, params)
        elif mode == "create":
            _job_create(job_dir, params)
        elif mode == "presenter":
            _job_presenter(job_dir, params)

    except Exception as exc:
        _status(job_dir, f"Erreur : {exc}", 0, error=str(exc))


# ─── Mode : Animate (photo → vidéo animée) ────────────────────────────────────

def _job_animate(job_dir: Path, params: dict):
    from pipelines.video_gen import generate_from_image, generate_from_text
    from pipelines.gpu_utils import get_vram_gb

    avatar = params.get("avatar_path")
    prompt = params["prompt"] or "a person moving naturally, cinematic, high quality"
    duration = params["duration"]
    output = str(job_dir / "output.mp4")

    def cb(pct): _status(job_dir, "Génération vidéo Wan2.1…", int(pct * 0.85))

    _status(job_dir, "Chargement du modèle Wan2.1…", 5)

    if avatar:
        generate_from_image(
            image_path=avatar,
            prompt=prompt,
            output_path=output,
            num_seconds=duration,
            progress_cb=cb,
        )
    else:
        generate_from_text(
            prompt=prompt,
            output_path=output,
            num_seconds=duration,
            progress_cb=cb,
        )

    _status(job_dir, "Terminé ✅", 100)


# ─── Mode : Create (texte → image FLUX → vidéo Wan2.1) ───────────────────────

def _job_create(job_dir: Path, params: dict):
    from pipelines.image_gen import generate_image
    from pipelines.video_gen import generate_from_image

    prompt = params["prompt"]
    if not prompt:
        raise ValueError("Un prompt texte est requis pour ce mode.")

    duration = params["duration"]

    # 1. Générer l'image avec FLUX
    _status(job_dir, "Génération image FLUX.1…", 5)
    img_path = str(job_dir / "frame.png")

    def img_cb(pct): _status(job_dir, "FLUX.1 — génération image…", int(5 + pct * 0.35))

    generate_image(prompt=prompt, output_path=img_path, progress_cb=img_cb)

    # 2. Animer avec Wan2.1
    _status(job_dir, "Animation Wan2.1…", 40)
    output = str(job_dir / "output.mp4")

    def vid_cb(pct): _status(job_dir, "Wan2.1 — animation…", int(40 + pct * 0.55))

    generate_from_image(
        image_path=img_path,
        prompt=prompt + ", smooth motion, cinematic",
        output_path=output,
        num_seconds=duration,
        progress_cb=vid_cb,
    )

    _status(job_dir, "Terminé ✅", 100)


# ─── Mode : Presenter (avatar parlant avec lip sync) ──────────────────────────

def _job_presenter(job_dir: Path, params: dict):
    from pipelines.tts import synthesize_sync
    from pipelines import lipsync

    text = params["text"]
    voice = params["voice"]
    voice_rate = params["voice_rate"]
    avatar = params.get("avatar_path")

    if not text:
        raise ValueError("Un texte est requis pour le mode présentateur.")

    # 1. Générer l'audio TTS
    _status(job_dir, "Synthèse vocale…", 10)
    audio_path = str(job_dir / "speech.mp3")
    synthesize_sync(text, voice, audio_path, rate=voice_rate)

    # 2. Générer une vidéo de base (avatar statique ou animé)
    _status(job_dir, "Préparation de l'avatar…", 25)
    base_video = str(job_dir / "base.mp4")

    if avatar:
        _make_static_video(avatar, audio_path, base_video)
    else:
        raise ValueError("Une photo avatar est requise pour le mode présentateur.")

    # 3. Appliquer le lip sync avec LatentSync
    output = str(job_dir / "output.mp4")

    if lipsync.is_available():
        _status(job_dir, "Lip sync LatentSync…", 50)
        def ls_cb(pct): _status(job_dir, "LatentSync — synchronisation lèvres…", int(50 + pct * 0.45))
        lipsync.run_lipsync(base_video, audio_path, output, progress_cb=ls_cb)
    else:
        # Pas de lip sync dispo → on retourne juste la vidéo de base
        import shutil
        shutil.copy(base_video, output)
        _status(
            job_dir,
            "⚠️ LatentSync non installé — vidéo sans lip sync",
            100
        )
        return

    _status(job_dir, "Terminé ✅", 100)


def _make_static_video(image_path: str, audio_path: str, output_path: str):
    """Create a video from a static image + audio using FFmpeg."""
    import subprocess
    cmd = [
        "ffmpeg", "-y",
        "-loop", "1",
        "-i", image_path,
        "-i", audio_path,
        "-c:v", "libx264",
        "-tune", "stillimage",
        "-c:a", "aac",
        "-b:a", "192k",
        "-pix_fmt", "yuv420p",
        "-shortest",
        "-vf", "scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2",
        output_path,
    ]
    result = subprocess.run(cmd, capture_output=True)
    if result.returncode != 0:
        raise RuntimeError(f"FFmpeg error: {result.stderr.decode()[-300:]}")


# ─── Status / Download ────────────────────────────────────────────────────────

@app.get("/api/status/{job_id}")
async def status(job_id: str):
    p = OUTPUT_DIR / job_id / "status.json"
    if not p.exists():
        return JSONResponse({"error": "Introuvable"}, status_code=404)
    return json.loads(p.read_text())


@app.get("/api/video/{job_id}")
async def video(job_id: str):
    p = OUTPUT_DIR / job_id / "output.mp4"
    if not p.exists():
        return JSONResponse({"error": "Vidéo pas encore prête"}, status_code=404)
    return FileResponse(str(p), media_type="video/mp4", filename=f"marvin-{job_id}.mp4")
