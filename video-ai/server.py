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
    from pipelines.liveportrait import is_available as lp_ok
    from pipelines.musetalk import is_available as muse_ok
    from pipelines.background import is_available as sam2_ok
    from pipelines.personalization import list_trained_persons
    from pipelines.image_gen import get_active_model_name
    info["talking_head_model"] = best_available()
    info["hallo2"] = hallo2_available()
    info["echomimic"] = echomimic_available()
    info["latsync"] = latsync_available()
    info["liveportrait"] = lp_ok()
    info["musetalk"] = muse_ok()
    info["sam2"] = sam2_ok()
    info["ip_adapter"] = (BASE_DIR / "models" / "ip-adapter-faceid_sdxl.bin").exists()
    info["trained_persons"] = list_trained_persons()
    info["image_model"] = get_active_model_name()
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


@app.get("/api/persons")
async def list_persons():
    from pipelines.personalization import list_trained_persons
    return {"persons": list_trained_persons()}


@app.post("/api/train")
async def train_person(
    person_name: str = Form(...),
    photos: list[UploadFile] = File(...),
):
    """Lance l'entraînement d'un LoRA personnalisé."""
    if len(photos) < 3:
        return JSONResponse({"error": "Minimum 3 photos requises."}, status_code=400)

    job_id = f"train_{person_name}_{uuid.uuid4().hex[:6]}"
    job_dir = OUTPUT_DIR / job_id
    job_dir.mkdir(parents=True)

    photos_dir = job_dir / "photos"
    photos_dir.mkdir()
    for photo in photos:
        ext = Path(photo.filename).suffix.lower()
        if ext in {".jpg", ".jpeg", ".png", ".webp"}:
            dest = photos_dir / f"{uuid.uuid4().hex[:8]}{ext}"
            dest.write_bytes(await photo.read())

    _status(job_dir, "Entraînement en attente…", 2)

    loop = asyncio.get_event_loop()
    loop.run_in_executor(executor, _run_training, job_id, job_dir, person_name, str(photos_dir))

    return {"job_id": job_id}


def _run_training(job_id, job_dir, person_name, photos_dir):
    try:
        from pipelines.personalization import train_person_lora

        def cb(pct, msg):
            _status(job_dir, msg, pct)

        train_person_lora(photos_dir, person_name, steps=500, progress_cb=cb)
        _status(job_dir, f"✅ LoRA '{person_name}' entraîné !", 100)
    except Exception as e:
        _status(job_dir, f"Erreur : {e}", 0, error=str(e))


@app.post("/api/generate")
async def generate(
    mode: str = Form(...),
    prompt: str = Form(""),
    text: str = Form(""),
    voice: str = Form("fr-FR-DeniseNeural"),
    voice_rate: str = Form("+0%"),
    avatar_name: str = Form(""),
    avatar_file: UploadFile = File(None),
    background_file: UploadFile = File(None),
    background_type: str = Form("none"),   # "none"|"blur"|"color"|"image"|"custom"
    background_color: str = Form("#1a1a2e"),
    person_name: str = Form(""),           # LoRA personnalisé
    duration: int = Form(4),
    quality: str = Form("auto"),
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

    # Background file
    bg_path = None
    if background_file and background_file.filename:
        ext = Path(background_file.filename).suffix
        bg_path = job_dir / f"bg{ext}"
        bg_path.write_bytes(await background_file.read())

    params = {
        "mode": mode,
        "prompt": prompt,
        "text": text,
        "voice": voice,
        "voice_rate": voice_rate,
        "avatar_path": str(avatar_path) if avatar_path else None,
        "background_type": background_type,
        "background_value": str(bg_path) if bg_path else background_color,
        "person_name": person_name,
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

def _apply_background(job_dir: Path, video_path: str, params: dict, start_pct: int, end_pct: int) -> str:
    """Applies background replacement if requested. Returns final video path."""
    bg_type = params.get("background_type", "none")
    if bg_type == "none":
        return video_path

    from pipelines.background import replace_background
    bg_out = str(job_dir / "bg_replaced.mp4")

    if bg_type == "blur":
        background = "blur"
    elif bg_type == "color":
        background = params.get("background_value", "#1a1a2e")
    elif bg_type in ("image", "custom"):
        background = params.get("background_value", "blur")
        if not background or not Path(background).exists():
            background = "blur"
    else:
        return video_path

    def bg_cb(p):
        _status(job_dir, "Remplacement du fond…", int(start_pct + p / 100 * (end_pct - start_pct)))

    ok = replace_background(video_path, bg_out, background=background, progress_cb=bg_cb)
    if ok and Path(bg_out).exists():
        Path(video_path).unlink(missing_ok=True)
        return bg_out
    return video_path


def _job_animate(job_dir: Path, params: dict):
    from pipelines.video_gen import generate_from_image, generate_from_text

    avatar = params.get("avatar_path")
    person_name = params.get("person_name", "")
    prompt = params["prompt"] or "a person moving naturally, cinematic, high quality"
    duration = params["duration"]
    enhance = params.get("enhance", True)
    color_preset = params.get("color_preset", "cinema")

    _status(job_dir, "Chargement du modèle Wan2.1…", 5)

    # LoRA personnalisé : génère une image cohérente avec la personne entraînée
    if person_name:
        from pipelines.personalization import is_trained, generate_with_lora
        if is_trained(person_name):
            _status(job_dir, f"Génération image LoRA ({person_name})…", 6)
            lora_img = str(job_dir / "lora_frame.png")
            ok = generate_with_lora(
                person_name=person_name,
                prompt=prompt,
                output_path=lora_img,
                progress_cb=lambda p: _status(job_dir, f"LoRA {person_name}…", int(6 + p * 0.18)),
            )
            if ok:
                avatar = lora_img
                _status(job_dir, "Image personnalisée générée ✓", 24)

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
            mid = str(job_dir / "enhanced.mp4")
            if enhance:
                from pipelines.enhancer import enhance_video
                enhance_video(
                    lp_out, mid,
                    upscale=True, face_enhance=True,
                    interpolate=True, target_fps=60,
                    color_preset=color_preset,
                    progress_cb=lambda p: _status(job_dir, "Post-processing…", int(45 + p * 0.45)),
                )
                Path(lp_out).unlink(missing_ok=True)
            else:
                import shutil; shutil.move(lp_out, mid)

            final = _apply_background(job_dir, mid, params, 90, 99)
            output = str(job_dir / "output.mp4")
            if final != output:
                import shutil; shutil.move(final, output)
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

    raw = str(job_dir / "raw.mp4")
    def cb(pct): _status(job_dir, "Génération vidéo Wan2.1…", int(pct * 0.82))

    if avatar:
        generate_from_image(
            image_path=avatar,
            prompt=prompt,
            output_path=raw,
            num_seconds=duration,
            enhance=enhance,
            color_preset=color_preset,
            progress_cb=cb,
        )
    else:
        generate_from_text(
            prompt=prompt,
            output_path=raw,
            num_seconds=duration,
            enhance=enhance,
            color_preset=color_preset,
            progress_cb=cb,
        )

    final = _apply_background(job_dir, raw, params, 85, 99)
    output = str(job_dir / "output.mp4")
    if final != output:
        import shutil; shutil.move(final, output)
    _status(job_dir, "Terminé ✅", 100)


# ─── Mode : Create (texte → image FLUX → vidéo Wan2.1) ───────────────────────

def _job_create(job_dir: Path, params: dict):
    from pipelines.image_gen import generate_image
    from pipelines.video_gen import generate_from_image

    prompt = params["prompt"]
    if not prompt:
        raise ValueError("Un prompt texte est requis pour ce mode.")

    person_name = params.get("person_name", "")
    duration = params["duration"]
    enhance = params.get("enhance", True)
    color_preset = params.get("color_preset", "cinema")

    # 1. Générer l'image (LoRA si personne entraînée, sinon FLUX standard)
    img_path = str(job_dir / "frame.png")
    used_lora = False

    if person_name:
        from pipelines.personalization import is_trained, generate_with_lora
        if is_trained(person_name):
            _status(job_dir, f"Génération image LoRA ({person_name})…", 5)
            used_lora = generate_with_lora(
                person_name=person_name,
                prompt=prompt,
                output_path=img_path,
                progress_cb=lambda p: _status(job_dir, f"LoRA {person_name}…", int(5 + p * 0.35)),
            )

    if not used_lora:
        _status(job_dir, "Génération image FLUX.1…", 5)
        def img_cb(pct): _status(job_dir, "FLUX.1 — génération image…", int(5 + pct * 0.35))
        generate_image(prompt=prompt, output_path=img_path, progress_cb=img_cb)

    # 2. Animer avec Wan2.1
    _status(job_dir, "Animation Wan2.1…", 40)
    raw = str(job_dir / "raw.mp4")

    def vid_cb(pct): _status(job_dir, "Wan2.1 — animation…", int(40 + pct * 0.50))

    generate_from_image(
        image_path=img_path,
        prompt=prompt + ", smooth motion, cinematic",
        output_path=raw,
        num_seconds=duration,
        enhance=enhance,
        color_preset=color_preset,
        progress_cb=vid_cb,
    )

    # 3. Remplacement du fond si demandé
    final = _apply_background(job_dir, raw, params, 90, 99)
    output = str(job_dir / "output.mp4")
    if final != output:
        import shutil; shutil.move(final, output)
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
    _status(job_dir, "Synthèse vocale…", 5)
    raw_audio = str(job_dir / "speech_raw.mp3")
    synthesize_sync(text, voice, raw_audio, rate=voice_rate)

    # 2. Amélioration audio (voix plus claire et professionnelle)
    _status(job_dir, "Amélioration audio…", 10)
    audio_path = str(job_dir / "speech.aac")
    try:
        from pipelines.audio_enhance import enhance_audio
        ok = enhance_audio(raw_audio, audio_path)
        if not ok:
            audio_path = raw_audio  # fallback audio brut
    except Exception:
        audio_path = raw_audio

    # 3. Préparer le portrait (crop du visage pour meilleurs résultats)
    _status(job_dir, "Détection et recadrage du visage…", 15)
    cropped_path = str(job_dir / "portrait.jpg")
    ok = crop_face_for_portrait(avatar, cropped_path)
    portrait = cropped_path if ok else avatar

    # 4. Talking head (LivePortrait+MuseTalk > MuseTalk > Hallo2 > EchoMimic > LatentSync)
    model_used = best_available()
    _status(job_dir, f"Génération avatar ({model_used})…", 20)
    raw_output = str(job_dir / "talking_raw.mp4")

    def th_cb(pct):
        _status(job_dir, f"{model_used} — animation en cours…", int(20 + pct * 0.58))

    generate_talking_head(portrait, audio_path, raw_output, progress_cb=th_cb)

    # 5. Post-processing (upscale + face restore + interpolation)
    enhanced = str(job_dir / "enhanced.mp4")
    if enhance:
        _status(job_dir, "Amélioration qualité…", 80)
        def en_cb(pct):
            _status(job_dir, "Post-processing…", int(80 + pct * 0.12))
        enhance_video(
            raw_output, enhanced,
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
        shutil.move(raw_output, enhanced)

    # 6. Remplacement du fond si demandé
    final = _apply_background(job_dir, enhanced, params, 93, 99)
    output = str(job_dir / "output.mp4")
    if final != output:
        import shutil; shutil.move(final, output)

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
