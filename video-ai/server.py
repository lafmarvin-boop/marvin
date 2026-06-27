#!/usr/bin/env python3
"""
Marvin Video AI — HeyGen-like local server
Run: uvicorn server:app --host 0.0.0.0 --port 8765 --reload
Then open http://<your-pc-ip>:8765 on your Android phone.
"""

import asyncio
import json
import os
import subprocess
import textwrap
import uuid
from pathlib import Path

import edge_tts
import numpy as np
from fastapi import FastAPI, File, Form, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from moviepy.editor import (
    AudioFileClip,
    ColorClip,
    CompositeVideoClip,
    ImageClip,
    TextClip,
    VideoFileClip,
    concatenate_videoclips,
)
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import io

app = FastAPI(title="Marvin Video AI")

BASE_DIR = Path(__file__).parent
OUTPUT_DIR = BASE_DIR / "outputs"
UPLOAD_DIR = BASE_DIR / "uploads"
AVATARS_DIR = BASE_DIR / "avatars"

for d in (OUTPUT_DIR, UPLOAD_DIR, AVATARS_DIR):
    d.mkdir(exist_ok=True)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ─── Helpers ─────────────────────────────────────────────────────────────────

THEMES = {
    "dark":     {"bg": (10, 12, 26),   "accent": (99, 102, 241),  "text": (255, 255, 255)},
    "ocean":    {"bg": (6, 25, 46),    "accent": (14, 165, 233),  "text": (255, 255, 255)},
    "sunset":   {"bg": (30, 10, 20),   "accent": (236, 72, 153),  "text": (255, 255, 255)},
    "forest":   {"bg": (8, 28, 18),    "accent": (34, 197, 94),   "text": (255, 255, 255)},
    "light":    {"bg": (245, 247, 255), "accent": (99, 102, 241), "text": (20, 20, 50)},
}

W, H = 1080, 1920  # Portrait 9:16


def hex_to_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))


def make_gradient_bg(color1, color2, size=(W, H)):
    img = Image.new("RGB", size)
    for y in range(size[1]):
        t = y / size[1]
        r = int(color1[0] * (1 - t) + color2[0] * t)
        g = int(color1[1] * (1 - t) + color2[1] * t)
        b = int(color1[2] * (1 - t) + color2[2] * t)
        for x in range(size[0]):
            img.putpixel((x, y), (r, g, b))
    return img


def make_text_frame(text, theme, frame_w, frame_h, font_size=52):
    img = Image.new("RGBA", (frame_w, frame_h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", font_size)
        font_small = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 36)
    except Exception:
        font = ImageFont.load_default()
        font_small = font

    lines = textwrap.wrap(text, width=28)
    text_color = theme["text"]
    accent = theme["accent"]

    # Decorative top bar
    draw.rectangle([(0, 0), (80, 8)], fill=accent)
    draw.rectangle([(90, 0), (130, 8)], fill=(*accent[:3], 120))

    y = 30
    for i, line in enumerate(lines):
        # Shadow
        draw.text((4, y + 4), line, font=font, fill=(0, 0, 0, 80))
        # Main text
        draw.text((0, y), line, font=font, fill=text_color)
        y += font_size + 14

    return img


def make_avatar_frame(avatar_path, size_hint=(700, 700)):
    img = Image.open(avatar_path).convert("RGBA")
    img.thumbnail(size_hint, Image.LANCZOS)

    # Soft circular mask
    mask = Image.new("L", img.size, 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse([(0, 0), img.size], fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(radius=4))

    result = Image.new("RGBA", img.size, (0, 0, 0, 0))
    result.paste(img, mask=mask)
    return result


def build_slide_image(text, theme_name, avatar_pil=None, slide_num=1, total_slides=1):
    theme = THEMES.get(theme_name, THEMES["dark"])
    bg_dark = tuple(max(0, c - 20) for c in theme["bg"])
    bg_img = make_gradient_bg(theme["bg"], bg_dark)
    composite = bg_img.convert("RGBA")

    # Accent circle decoration
    overlay = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ov_draw = ImageDraw.Draw(overlay)
    r = 600
    ov_draw.ellipse([(W - r, -r // 2), (W + r // 2, r // 2)],
                    fill=(*theme["accent"], 20))
    composite = Image.alpha_composite(composite, overlay)

    if avatar_pil:
        av = avatar_pil.copy()
        av.thumbnail((680, 680), Image.LANCZOS)
        av_x = (W - av.width) // 2
        av_y = 120
        composite.paste(av, (av_x, av_y), av)
        text_top = av_y + av.height + 60
    else:
        text_top = 300

    text_frame_h = H - text_top - 140
    txt_img = make_text_frame(text, theme, W - 80, text_frame_h)
    composite.paste(txt_img, (40, text_top), txt_img)

    # Progress bar
    bar_y = H - 60
    bar_w = int(W * slide_num / max(total_slides, 1))
    prog_layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    pd = ImageDraw.Draw(prog_layer)
    pd.rectangle([(0, bar_y), (W, bar_y + 12)], fill=(*theme["bg"], 180))
    pd.rectangle([(0, bar_y), (bar_w, bar_y + 12)], fill=(*theme["accent"], 220))
    composite = Image.alpha_composite(composite, prog_layer)

    return composite.convert("RGB")


# ─── API ─────────────────────────────────────────────────────────────────────

@app.get("/", response_class=HTMLResponse)
async def serve_index():
    html_path = BASE_DIR / "frontend" / "index.html"
    return HTMLResponse(html_path.read_text())


@app.get("/api/voices")
async def list_voices():
    voices = await edge_tts.list_voices()
    filtered = [
        {"name": v["ShortName"], "label": v["FriendlyName"]}
        for v in voices
        if v["Locale"].startswith(("fr-", "en-"))
    ]
    return {"voices": filtered}


@app.get("/api/avatars")
async def list_avatars():
    exts = {".jpg", ".jpeg", ".png", ".webp"}
    files = [f.name for f in AVATARS_DIR.iterdir() if f.suffix.lower() in exts]
    return {"avatars": files}


@app.post("/api/generate")
async def generate_video(
    text: str = Form(...),
    voice: str = Form("fr-FR-DeniseNeural"),
    theme: str = Form("dark"),
    avatar_file: UploadFile = File(None),
    avatar_name: str = Form(""),
    lipsync: bool = Form(False),
):
    if not text.strip():
        return JSONResponse({"error": "Texte vide"}, status_code=400)

    job_id = str(uuid.uuid4())[:8]
    job_dir = OUTPUT_DIR / job_id
    job_dir.mkdir(parents=True)

    status_path = job_dir / "status.json"

    def set_status(step, pct):
        status_path.write_text(json.dumps({"step": step, "pct": pct}))

    set_status("Initialisation…", 5)

    # ── 1. TTS ──────────────────────────────────────────────────────────────
    audio_path = job_dir / "audio.mp3"
    set_status("Génération de la voix…", 15)
    communicate = edge_tts.Communicate(text, voice)
    await communicate.save(str(audio_path))

    # ── 2. Avatar ────────────────────────────────────────────────────────────
    avatar_pil = None
    saved_avatar_path = None

    if avatar_file and avatar_file.filename:
        ext = Path(avatar_file.filename).suffix
        saved_avatar_path = job_dir / f"avatar{ext}"
        data = await avatar_file.read()
        saved_avatar_path.write_bytes(data)
        avatar_pil = make_avatar_frame(saved_avatar_path)
    elif avatar_name:
        candidate = AVATARS_DIR / avatar_name
        if candidate.exists():
            saved_avatar_path = candidate
            avatar_pil = make_avatar_frame(candidate)

    # ── 3. Build video ───────────────────────────────────────────────────────
    set_status("Composition de la vidéo…", 40)
    audio_clip = AudioFileClip(str(audio_path))
    duration = audio_clip.duration

    # Split text into ~3-second segments
    sentences = [s.strip() for s in text.replace("!", ".").replace("?", ".").split(".") if s.strip()]
    if not sentences:
        sentences = [text]

    time_per_slide = duration / len(sentences)
    video_clips = []

    for i, sentence in enumerate(sentences):
        set_status(f"Diapo {i+1}/{len(sentences)}…", 40 + int(40 * i / len(sentences)))
        slide_img = build_slide_image(
            sentence, theme, avatar_pil,
            slide_num=i + 1, total_slides=len(sentences)
        )
        slide_np = np.array(slide_img)
        clip = ImageClip(slide_np, duration=time_per_slide)
        clip = clip.fadein(0.3).fadeout(0.3)
        video_clips.append(clip)

    set_status("Encodage final…", 85)
    final_video = concatenate_videoclips(video_clips, method="compose")
    final_video = final_video.set_audio(audio_clip)

    output_path = job_dir / "output.mp4"
    final_video.write_videofile(
        str(output_path),
        fps=24,
        codec="libx264",
        audio_codec="aac",
        preset="fast",
        verbose=False,
        logger=None,
    )

    set_status("Terminé !", 100)

    return {"job_id": job_id, "video_url": f"/api/video/{job_id}"}


@app.get("/api/status/{job_id}")
async def job_status(job_id: str):
    status_path = OUTPUT_DIR / job_id / "status.json"
    if not status_path.exists():
        return JSONResponse({"error": "Introuvable"}, status_code=404)
    return json.loads(status_path.read_text())


@app.get("/api/video/{job_id}")
async def get_video(job_id: str):
    video_path = OUTPUT_DIR / job_id / "output.mp4"
    if not video_path.exists():
        return JSONResponse({"error": "Vidéo introuvable"}, status_code=404)
    return FileResponse(str(video_path), media_type="video/mp4", filename=f"video-{job_id}.mp4")


@app.post("/api/upload-avatar")
async def upload_avatar(file: UploadFile = File(...)):
    ext = Path(file.filename).suffix.lower()
    if ext not in {".jpg", ".jpeg", ".png", ".webp"}:
        return JSONResponse({"error": "Format non supporté"}, status_code=400)
    name = f"{uuid.uuid4().hex[:8]}{ext}"
    dest = AVATARS_DIR / name
    dest.write_bytes(await file.read())
    return {"name": name, "url": f"/api/avatar-thumb/{name}"}


@app.get("/api/avatar-thumb/{name}")
async def avatar_thumb(name: str):
    path = AVATARS_DIR / name
    if not path.exists():
        return JSONResponse({"error": "Introuvable"}, status_code=404)
    return FileResponse(str(path))
