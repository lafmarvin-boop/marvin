#!/usr/bin/env bash
# ─────────────────────────────────────────────────────
#  Marvin Video AI — Démarrage rapide
# ─────────────────────────────────────────────────────
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Crée l'environnement virtuel si besoin
if [ ! -d ".venv" ]; then
  echo "📦 Création de l'environnement virtuel…"
  python3 -m venv .venv
fi

source .venv/bin/activate

# Installe les dépendances si besoin
if ! python -c "import fastapi" 2>/dev/null; then
  echo "📦 Installation des dépendances…"
  pip install -q -r requirements.txt
fi

# Trouve l'IP locale pour afficher l'URL mobile
LOCAL_IP=$(hostname -I | awk '{print $1}' 2>/dev/null || ip route get 1 | awk '{print $7}' | head -1)

echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║         🎬  Marvin Video AI  démarré             ║"
echo "╠══════════════════════════════════════════════════╣"
echo "║  PC local :  http://localhost:8765               ║"
echo "║  Téléphone : http://${LOCAL_IP}:8765             ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""
echo "  → Connecte ton téléphone au même WiFi que ton PC"
echo "  → Ouvre l'URL téléphone dans Chrome / Firefox"
echo "  → Ajoute à l'écran d'accueil pour une expérience PWA"
echo ""

uvicorn server:app --host 0.0.0.0 --port 8765 --reload
