"""
Module optionnel : Lip Sync avec Wav2Lip ou SadTalker.
Ne pas importer dans server.py par défaut — active-le manuellement
une fois que tu as installé les modèles.

Installation Wav2Lip :
  git clone https://github.com/Rudrabha/Wav2Lip.git
  cd Wav2Lip && pip install -r requirements.txt
  # Télécharge les poids depuis le README de Wav2Lip

Installation SadTalker (meilleure qualité) :
  git clone https://github.com/OpenTalker/SadTalker.git
  cd SadTalker && pip install -r requirements.txt
  bash scripts/download_models.sh
"""

import subprocess
import shutil
from pathlib import Path


def wav2lip_animate(avatar_image: str, audio_file: str, output_video: str,
                    wav2lip_dir: str = "./Wav2Lip") -> bool:
    """
    Anime un avatar avec Wav2Lip.
    Retourne True si succès.
    """
    checkpoint = Path(wav2lip_dir) / "checkpoints" / "wav2lip_gan.pth"
    if not checkpoint.exists():
        raise FileNotFoundError(
            "Modèle Wav2Lip introuvable. "
            "Télécharge wav2lip_gan.pth dans Wav2Lip/checkpoints/"
        )

    cmd = [
        "python", f"{wav2lip_dir}/inference.py",
        "--checkpoint_path", str(checkpoint),
        "--face", avatar_image,
        "--audio", audio_file,
        "--outfile", output_video,
        "--resize_factor", "1",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    return result.returncode == 0


def sadtalker_animate(avatar_image: str, audio_file: str, output_dir: str,
                      sadtalker_dir: str = "./SadTalker") -> str | None:
    """
    Anime un avatar avec SadTalker.
    Retourne le chemin de la vidéo générée ou None.
    """
    script = Path(sadtalker_dir) / "inference.py"
    if not script.exists():
        raise FileNotFoundError("SadTalker non trouvé. Clone le repo d'abord.")

    cmd = [
        "python", str(script),
        "--driven_audio", audio_file,
        "--source_image", avatar_image,
        "--result_dir", output_dir,
        "--still",
        "--enhancer", "gfpgan",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, cwd=sadtalker_dir)
    if result.returncode != 0:
        return None

    # SadTalker crée un fichier dans output_dir
    outputs = list(Path(output_dir).glob("*.mp4"))
    return str(outputs[0]) if outputs else None
