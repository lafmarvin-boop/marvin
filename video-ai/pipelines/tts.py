"""
Text-to-speech via edge-tts (Microsoft Neural voices).
Excellent quality for French and English, completely free.
"""

from __future__ import annotations

import asyncio
from pathlib import Path

import edge_tts


VOICES = {
    "fr": [
        {"id": "fr-FR-DeniseNeural",  "label": "Denise — Femme FR",   "flag": "🇫🇷"},
        {"id": "fr-FR-HenriNeural",   "label": "Henri — Homme FR",    "flag": "🇫🇷"},
        {"id": "fr-FR-EloiseNeural",  "label": "Éloïse — Femme FR",   "flag": "🇫🇷"},
        {"id": "fr-CA-SylvieNeural",  "label": "Sylvie — Femme CA",   "flag": "🇨🇦"},
        {"id": "fr-CA-JeanNeural",    "label": "Jean — Homme CA",     "flag": "🇨🇦"},
    ],
    "en": [
        {"id": "en-US-JennyNeural",   "label": "Jenny — Femme US",    "flag": "🇺🇸"},
        {"id": "en-US-GuyNeural",     "label": "Guy — Homme US",      "flag": "🇺🇸"},
        {"id": "en-GB-SoniaNeural",   "label": "Sonia — Femme UK",    "flag": "🇬🇧"},
        {"id": "en-GB-RyanNeural",    "label": "Ryan — Homme UK",     "flag": "🇬🇧"},
    ],
}


async def synthesize(text: str, voice: str, output_path: str, rate: str = "+0%", pitch: str = "+0Hz"):
    """Generate a WAV/MP3 audio file from text using edge-tts."""
    communicate = edge_tts.Communicate(text, voice, rate=rate, pitch=pitch)
    await communicate.save(output_path)


def synthesize_sync(text: str, voice: str, output_path: str, rate: str = "+0%", pitch: str = "+0Hz"):
    asyncio.run(synthesize(text, voice, output_path, rate, pitch))


def get_voices() -> list[dict]:
    result = []
    for lang, voices in VOICES.items():
        for v in voices:
            result.append({"id": v["id"], "label": f"{v['flag']} {v['label']}", "lang": lang})
    return result
