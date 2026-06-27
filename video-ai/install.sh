#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════
#  Marvin Video AI — Installation automatique complète
#  Exécute ce script UNE SEULE FOIS pour tout installer.
#  Durée : 10-40 min selon ta connexion (modèles = 20-50 GB)
# ══════════════════════════════════════════════════════════════════════
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
log()  { echo -e "${CYAN}▶ $1${NC}"; }
ok()   { echo -e "${GREEN}✓ $1${NC}"; }
warn() { echo -e "${YELLOW}⚠ $1${NC}"; }
err()  { echo -e "${RED}✗ $1${NC}"; }

# ── 1. Venv Python ────────────────────────────────────────────────────
log "Création de l'environnement Python…"
if [ ! -d ".venv" ]; then
  python3 -m venv .venv
fi
source .venv/bin/activate
pip install -q --upgrade pip

# ── 2. Détection GPU ─────────────────────────────────────────────────
log "Détection du GPU…"
VRAM_GB=0
GPU_NAME="CPU"
CUDA_AVAILABLE=false

if python3 -c "import torch; assert torch.cuda.is_available()" 2>/dev/null; then
  CUDA_AVAILABLE=true
  GPU_NAME=$(python3 -c "import torch; print(torch.cuda.get_device_properties(0).name)")
  VRAM_GB=$(python3 -c "import torch; print(int(torch.cuda.get_device_properties(0).total_memory/1e9))")
fi

echo ""
echo "╔══════════════════════════════════════════════╗"
echo "║  GPU détecté : $GPU_NAME"
echo "║  VRAM        : ${VRAM_GB} GB"
echo "║  CUDA        : $CUDA_AVAILABLE"
echo "╚══════════════════════════════════════════════╝"
echo ""

if [ "$VRAM_GB" -lt 8 ] 2>/dev/null && [ "$CUDA_AVAILABLE" = true ]; then
  warn "GPU avec moins de 8 GB VRAM détecté."
  warn "La génération vidéo fonctionnera mais sera lente."
  warn "Recommandé : RTX 3060 (12GB) ou mieux."
fi

# ── 3. Packages Python principaux ────────────────────────────────────
log "Installation des packages Python…"

# PyTorch avec CUDA si disponible
if [ "$CUDA_AVAILABLE" = true ]; then
  pip install -q torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu121
else
  pip install -q torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cpu
fi

pip install -q \
  fastapi \
  "uvicorn[standard]" \
  edge-tts \
  python-multipart \
  diffusers \
  transformers \
  accelerate \
  huggingface_hub \
  sentencepiece \
  Pillow \
  numpy \
  imageio \
  imageio-ffmpeg \
  ffmpeg-python

ok "Packages Python installés."

# ── 4. Vérifier FFmpeg ───────────────────────────────────────────────
log "Vérification de FFmpeg…"
if ! command -v ffmpeg &>/dev/null; then
  warn "FFmpeg non trouvé. Installation…"
  if command -v apt-get &>/dev/null; then
    sudo apt-get install -y ffmpeg
  elif command -v brew &>/dev/null; then
    brew install ffmpeg
  else
    err "Installe FFmpeg manuellement : https://ffmpeg.org/download.html"
    exit 1
  fi
fi
ok "FFmpeg disponible."

# ── 5. Modèles Wan2.1 ────────────────────────────────────────────────
log "Téléchargement des modèles Wan2.1…"
mkdir -p models

python3 << 'PYEOF'
from huggingface_hub import snapshot_download
import os, sys

vram = 0
try:
    import torch
    if torch.cuda.is_available():
        vram = torch.cuda.get_device_properties(0).total_memory / 1e9
except:
    pass

cache = os.path.join(os.path.dirname(__file__), "models")

print(f"VRAM disponible : {vram:.1f} GB")

# Toujours télécharger la version 1.3B (légère, fonctionne partout)
print("⬇ Téléchargement Wan2.1-T2V-1.3B (≈5 GB)…")
snapshot_download("Wan-AI/Wan2.1-T2V-1.3B", cache_dir=cache)
print("✓ Wan2.1-T2V-1.3B téléchargé.")

if vram >= 12:
    print("⬇ Téléchargement Wan2.1-I2V-14B-480P (≈28 GB)…")
    snapshot_download("Wan-AI/Wan2.1-I2V-14B-480P", cache_dir=cache)
    print("✓ Wan2.1-I2V-14B-480P téléchargé.")

if vram >= 16:
    print("⬇ Téléchargement Wan2.1-T2V-14B (≈28 GB)…")
    snapshot_download("Wan-AI/Wan2.1-T2V-14B", cache_dir=cache)
    print("✓ Wan2.1-T2V-14B téléchargé.")
PYEOF

ok "Modèles Wan2.1 installés."

# ── 6. Modèles FLUX.1 ────────────────────────────────────────────────
log "Téléchargement de FLUX.1…"

python3 << 'PYEOF'
from huggingface_hub import snapshot_download
import os, torch

vram = 0
try:
    if torch.cuda.is_available():
        vram = torch.cuda.get_device_properties(0).total_memory / 1e9
except:
    pass

cache = os.path.join(os.path.dirname(__file__), "models")

if vram >= 16:
    model = "black-forest-labs/FLUX.1-dev"
    size = "≈24 GB"
elif vram >= 10:
    model = "black-forest-labs/FLUX.1-schnell"
    size = "≈16 GB"
else:
    model = "stabilityai/stable-diffusion-xl-base-1.0"
    size = "≈7 GB"

print(f"⬇ Téléchargement {model} ({size})…")
snapshot_download(model, cache_dir=cache)
print(f"✓ {model} téléchargé.")
PYEOF

ok "FLUX.1 installé."

# ── 7. LatentSync (lip sync) ─────────────────────────────────────────
log "Installation de LatentSync (lip sync)…"

if [ ! -d "LatentSync" ]; then
  git clone https://github.com/bytedance/LatentSync.git
fi

cd LatentSync
pip install -q -r requirements.txt 2>/dev/null || pip install -q -r requirements.txt --no-deps

# Télécharger les checkpoints
mkdir -p checkpoints/whisper

python3 << 'PYEOF'
from huggingface_hub import hf_hub_download
import os

dest = "checkpoints"
os.makedirs(dest, exist_ok=True)

print("⬇ Téléchargement checkpoint LatentSync…")
hf_hub_download(
    "ByteDance/LatentSync-1.5",
    filename="latentsync_unet.pt",
    local_dir=dest,
)
print("✓ LatentSync checkpoint téléchargé.")

whisper_dest = os.path.join(dest, "whisper")
os.makedirs(whisper_dest, exist_ok=True)
print("⬇ Téléchargement Whisper tiny…")
hf_hub_download(
    "openai/whisper-tiny",
    filename="model.pt",
    local_dir=whisper_dest,
)
# Renommer en tiny.pt
import shutil
shutil.move(os.path.join(whisper_dest, "model.pt"), os.path.join(whisper_dest, "tiny.pt"))
print("✓ Whisper tiny téléchargé.")
PYEOF

cd "$SCRIPT_DIR"
ok "LatentSync installé."

# ── 8. Résumé final ───────────────────────────────────────────────────
LOCAL_IP=$(hostname -I | awk '{print $1}' 2>/dev/null || ip route get 1 2>/dev/null | awk '{print $7}' | head -1)

echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║   ✅  Installation terminée avec succès !        ║"
echo "╠══════════════════════════════════════════════════╣"
echo "║  Pour démarrer :  bash start.sh                  ║"
echo "║                                                  ║"
echo "║  Depuis ton téléphone (même WiFi) :              ║"
echo "║  http://${LOCAL_IP}:8765                         ║"
echo "╚══════════════════════════════════════════════════╝"
