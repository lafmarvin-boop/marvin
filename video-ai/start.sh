#!/usr/bin/env bash
# Marvin Video AI — Démarrage rapide (après install.sh)
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ ! -d ".venv" ]; then
  echo "❌ Lance install.sh d'abord !"
  exit 1
fi

source .venv/bin/activate

LOCAL_IP=$(hostname -I | awk '{print $1}' 2>/dev/null || ip route get 1 2>/dev/null | awk '{print $7}' | head -1)

echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║        🎬  Marvin Video AI                       ║"
echo "╠══════════════════════════════════════════════════╣"
echo "║  PC local :    http://localhost:8765             ║"
echo "║  Téléphone :   http://${LOCAL_IP}:8765           ║"
echo "║                                                  ║"
echo "║  → Même WiFi que ton PC requis                   ║"
echo "║  → Chrome : Menu → Ajouter à l'écran d'accueil  ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

uvicorn server:app --host 0.0.0.0 --port 8765
