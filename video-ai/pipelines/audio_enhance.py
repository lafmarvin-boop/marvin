"""
Amélioration audio — voix plus claire, plus professionnelle.

Traitements appliqués :
  1. Débruitage  (noisereduce)      — supprime le bruit de fond
  2. Normalisation (ffmpeg loudnorm) — volume cohérent, standard -14 LUFS
  3. EQ voix (ffmpeg)               — boost des fréquences de présence (2-5 kHz)
  4. Légère compression (ffmpeg)    — voix plus constante, évite les creux
"""

from __future__ import annotations

import subprocess
from pathlib import Path


def enhance_audio(
    input_path: str,
    output_path: str,
    denoise: bool = True,
    normalize: bool = True,
    eq_voice: bool = True,
) -> bool:
    """
    Applique toute la chaîne d'amélioration audio.
    Retourne True si succès.
    """
    try:
        current = input_path
        tmp = Path(output_path).parent

        # 1. Débruitage avec noisereduce
        if denoise:
            denoised = str(tmp / "_denoised.wav")
            if _denoise(current, denoised):
                current = denoised

        # 2. EQ voix + compression + normalisation (tout en une passe FFmpeg)
        filters = []

        if eq_voice:
            # Boost présence voix (2-5kHz) + atténuation bas fond (< 100Hz)
            filters += [
                "highpass=f=80",            # coupe bruit basse fréquence
                "equalizer=f=250:t=o:w=100:g=-2",   # atténue légèrement les boîtes
                "equalizer=f=3000:t=o:w=2000:g=3",  # boost présence/intelligibilité
                "equalizer=f=8000:t=o:w=3000:g=1",  # légère brillance
            ]

        if normalize:
            filters.append("loudnorm=I=-14:TP=-1:LRA=7")  # standard streaming

        # Compression douce
        filters.append("acompressor=threshold=-20dB:ratio=3:attack=5:release=50:makeup=2dB")

        if filters:
            processed = str(tmp / "_processed.wav")
            cmd = [
                "ffmpeg", "-y",
                "-i", current,
                "-af", ",".join(filters),
                "-ar", "44100",
                processed,
            ]
            result = subprocess.run(cmd, capture_output=True)
            if result.returncode == 0:
                current = processed

        # Convertir en AAC final
        subprocess.run([
            "ffmpeg", "-y",
            "-i", current,
            "-c:a", "aac", "-b:a", "256k",
            output_path,
        ], capture_output=True, check=True)

        # Nettoyage
        for tmp_f in [str(tmp / "_denoised.wav"), str(tmp / "_processed.wav")]:
            Path(tmp_f).unlink(missing_ok=True)

        return True

    except Exception:
        return False


def _denoise(input_path: str, output_path: str) -> bool:
    """Débruite l'audio avec noisereduce."""
    try:
        import noisereduce as nr
        import soundfile as sf
        import numpy as np

        data, rate = sf.read(input_path)
        if data.ndim > 1:
            data = data.mean(axis=1)

        # Utilise les 0.5 premières secondes comme profil de bruit
        noise_sample_end = min(int(rate * 0.5), len(data))
        noise_sample = data[:noise_sample_end]

        reduced = nr.reduce_noise(
            y=data,
            sr=rate,
            y_noise=noise_sample,
            prop_decrease=0.75,
            stationary=False,
        )

        sf.write(output_path, reduced, rate)
        return True
    except Exception:
        return False


def merge_audio_video(video_path: str, audio_path: str, output_path: str) -> bool:
    """Remplace la piste audio d'une vidéo."""
    try:
        subprocess.run([
            "ffmpeg", "-y",
            "-i", video_path,
            "-i", audio_path,
            "-c:v", "copy",
            "-c:a", "aac", "-b:a", "256k",
            "-map", "0:v:0", "-map", "1:a:0",
            "-shortest",
            output_path,
        ], capture_output=True, check=True)
        return True
    except Exception:
        return False
