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
    from pipelines.talking_head import best_available, hallo2_available, echomimic_available, latsync_available
    info["talking_head_model"] = best_available()
    info["hallo2"] = hallo2_available()
    info["echomimic"] = echomimic_available()
    info["latsync"] = latsync_available()
    from pipelines.face_consistency import extract_face_embedding
    info["ip_adapter"] = (BASE_DIR / "models" / "ip-adapter-faceid_sdxl.bin").exists()
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
        "enhance": quality != "fast",
        "color_preset": "cinema",
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

    enhance = params.get("enhance", True)
    color_preset = params.get("color_preset", "cinema")

    # LivePortrait disponible + avatar → animation portrait ultra-réaliste
    from pipelines.liveportrait import is_available as lp_ok
    if avatar and lp_ok():
        _status(job_dir, "LivePortrait — animation portrait…", 8)
        lp_out = str(job_dir / "lp_animated.mp4")
        try:
            from pipelines.liveportrait import animate_from_image
            animate_from_image(
                source_image=avatar,
                output_path=lp_out,
                expression_preset="talking",
                progress_cb=lambda p: _status(job_dir, "LivePortrait…", int(8 + p * 0.35)),
            )
            # Post-processing directement sur la vidéo LivePortrait
            if enhance:
                from pipelines.enhancer import enhance_video
                enhance_video(
                    lp_out, output,
                    upscale=True, face_enhance=True,
                    interpolate=True, target_fps=60,
                    color_preset=color_preset,
                    progress_cb=lambda p: _status(job_dir, "Post-processing…", int(45 + p * 0.54)),
                )
                Path(lp_out).unlink(missing_ok=True)
            else:
                import shutil; shutil.move(lp_out, output)
            _status(job_dir, "Terminé ✅ (LivePortrait)", 100)
            return
        except Exception:
            pass  # fallback Wan2.1

    # IP-Adapter FaceID : cohérence visage maximale avant animation Wan2.1
    if avatar:
        from pipelines.face_consistency import generate_with_face
        face_img = str(job_dir / "face_consistent.png")
        used_ip = generate_with_face(
            face_image_path=avatar,
            prompt=prompt,
            output_path=face_img,
            progress_cb=lambda p: _status(job_dir, "IP-Adapter FaceID…", int(5 + p * 0.2)),
        )
        if used_ip:
            avatar = face_img
            _status(job_dir, "Cohérence visage appliquée ✓", 25)

    if avatar:
        generate_from_image(
            image_path=avatar,
            prompt=prompt,
            output_path=output,
            num_seconds=duration,
            enhance=enhance,
            color_preset=color_preset,
            progress_cb=cb,
        )
    else:
        generate_from_text(
            prompt=prompt,
            output_path=output,
            num_seconds=duration,
            enhance=enhance,
            color_preset=color_preset,
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
    enhance = params.get("enhance", True)
    color_preset = params.get("color_preset", "cinema")

    def vid_cb(pct): _status(job_dir, "Wan2.1 — animation…", int(40 + pct * 0.55))

    generate_from_image(
        image_path=img_path,
        prompt=prompt + ", smooth motion, cinematic",
        output_path=output,
        num_seconds=duration,
        enhance=enhance,
        color_preset=color_preset,
        progress_cb=vid_cb,
    )

    _status(job_dir, "Terminé ✅", 100)


# ─── Mode : Presenter (avatar parlant avec lip sync) ──────────────────────────

def _job_presenter(job_dir: Path, params: dict):
    from pipelines.tts import synthesize_sync
    from pipelines.talking_head import generate_talking_head, best_available
    from pipelines.face_consistency import crop_face_for_portrait
    from pipelines.enhancer import enhance_video

    text = params["text"]
    voice = params["voice"]
    voice_rate = params["voice_rate"]
    avatar = params.get("avatar_path")
    enhance = params.get("enhance", True)

    if not text:
        raise ValueError("Un texte est requis pour le mode présentateur.")
    if not avatar:
        raise ValueError("Une photo avatar est requise pour le mode présentateur.")

    # 1. TTS
    _status(job_dir, "Synthèse vocale…", 8)
    audio_path = str(job_dir / "speech.mp3")
    synthesize_sync(text, voice, audio_path, rate=voice_rate)

    # 2. Préparer le portrait (crop du visage pour meilleurs résultats)
    _status(job_dir, "Détection et recadrage du visage…", 15)
    cropped_path = str(job_dir / "portrait.jpg")
    ok = crop_face_for_portrait(avatar, cropped_path)
    portrait = cropped_path if ok else avatar

    # 3. Talking head (Hallo2 > EchoMimic > LatentSync)
    model_used = best_available()
    _status(job_dir, f"Génération avatar ({model_used})…", 20)
    raw_output = str(job_dir / "talking_raw.mp4")

    def th_cb(pct):
        _status(job_dir, f"{model_used} — animation en cours…", int(20 + pct * 0.6))

    generate_talking_head(portrait, audio_path, raw_output, progress_cb=th_cb)

    # 4. Post-processing
    output = str(job_dir / "output.mp4")
    if enhance:
        _status(job_dir, "Amélioration qualité…", 82)
        def en_cb(pct):
            _status(job_dir, "Post-processing…", int(82 + pct * 0.16))
        enhance_video(
            raw_output, output,
            upscale=True,
            face_enhance=True,
            interpolate=True,
            target_fps=60,
            color_preset="cinema",
            progress_cb=en_cb,
        )
        Path(raw_output).unlink(missing_ok=True)
    else:
        import shutil
        shutil.move(raw_output, output)

    _status(job_dir, f"Terminé ✅ (via {model_used})", 100)


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
