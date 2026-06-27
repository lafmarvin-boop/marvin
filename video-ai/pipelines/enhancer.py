"""
Post-processing pipeline for maximum realism:
1. CodeFormer   — restauration visage (meilleur que GFPGAN, plus naturel)
2. Real-ESRGAN  — 4x upscaling de chaque frame
3. FFmpeg       — interpolation frames (16fps → 60fps) + color grading
"""

from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path
from typing import Callable


# ─── Real-ESRGAN ─────────────────────────────────────────────────────────────

def upscale_image(input_path: str, output_path: str, scale: int = 4) -> bool:
    """
    Upscale a single image with Real-ESRGAN.
    Requires: pip install realesrgan basicsr
    Model: RealESRGAN_x4plus.pth (downloaded by install.sh)
    """
    try:
        import cv2
        import numpy as np
        from basicsr.archs.rrdbnet_arch import RRDBNet
        from realesrgan import RealESRGANer

        model_path = Path(__file__).parent.parent / "models" / "RealESRGAN_x4plus.pth"
        if not model_path.exists():
            return False

        model = RRDBNet(
            num_in_ch=3, num_out_ch=3, num_feat=64,
            num_block=23, num_grow_ch=32, scale=scale
        )
        upsampler = RealESRGANer(
            scale=scale,
            model_path=str(model_path),
            model=model,
            tile=512,
            tile_pad=10,
            pre_pad=0,
            half=True,
        )

        img = cv2.imread(input_path, cv2.IMREAD_COLOR)
        output, _ = upsampler.enhance(img, outscale=scale)
        cv2.imwrite(output_path, output)
        return True
    except ImportError:
        return False
    except Exception:
        return False


def enhance_faces(input_path: str, output_path: str, fidelity: float = 0.7) -> bool:
    """
    Restaure et améliore les visages.
    Essaie CodeFormer en premier (meilleure qualité), sinon GFPGAN.

    fidelity : 0.0 = max enhancement, 1.0 = max fidelity to original.
               0.7 est le meilleur compromis qualité/naturalisme.
    """
    # Essai CodeFormer (prioritaire)
    if _enhance_with_codeformer(input_path, output_path, fidelity):
        return True
    # Fallback GFPGAN
    return _enhance_with_gfpgan(input_path, output_path)


def _enhance_with_codeformer(input_path: str, output_path: str, fidelity: float = 0.7) -> bool:
    """CodeFormer — restauration faciale SOTA, résultats plus naturels que GFPGAN."""
    try:
        import subprocess, sys
        from pathlib import Path

        cf_dir = Path(__file__).parent.parent / "CodeFormer"
        script  = cf_dir / "inference_codeformer.py"
        ckpt    = cf_dir / "weights" / "CodeFormer" / "codeformer.pth"

        if not script.exists() or not ckpt.exists():
            return False

        out_dir = Path(output_path).parent / "_cf_tmp"
        out_dir.mkdir(exist_ok=True)

        result = subprocess.run(
            [
                sys.executable, str(script),
                "-w",            str(fidelity),
                "--input_path",  input_path,
                "--output_path", str(out_dir),
                "--face_upsample",
                "--bg_upsampler", "realesrgan",
            ],
            capture_output=True,
            text=True,
            cwd=str(cf_dir),
        )

        if result.returncode != 0:
            import shutil; shutil.rmtree(out_dir, ignore_errors=True)
            return False

        # CodeFormer écrit dans out_dir/restored_faces/ ou out_dir/final_results/
        for sub in ("final_results", "restored_faces", ""):
            found = list((out_dir / sub).glob("*.png")) if sub else list(out_dir.glob("*.png"))
            if found:
                import shutil
                shutil.copy(str(found[0]), output_path)
                shutil.rmtree(out_dir, ignore_errors=True)
                return True

        import shutil; shutil.rmtree(out_dir, ignore_errors=True)
        return False

    except Exception:
        return False


def _enhance_with_gfpgan(input_path: str, output_path: str) -> bool:
    """GFPGAN — fallback si CodeFormer non disponible."""
    try:
        import cv2
        from gfpgan import GFPGANer

        model_path = Path(__file__).parent.parent / "models" / "GFPGANv1.4.pth"
        if not model_path.exists():
            return False

        restorer = GFPGANer(
            model_path=str(model_path),
            upscale=1,
            arch="clean",
            channel_multiplier=2,
        )
        img = cv2.imread(input_path, cv2.IMREAD_COLOR)
        _, _, restored = restorer.enhance(img, has_aligned=False, only_center_face=False)
        cv2.imwrite(output_path, restored)
        return True
    except Exception:
        return False


# ─── FFmpeg post-processing ───────────────────────────────────────────────────

def interpolate_fps(input_path: str, output_path: str, target_fps: int = 60) -> bool:
    """Interpolate frames to target FPS using FFmpeg minterpolate."""
    cmd = [
        "ffmpeg", "-y", "-i", input_path,
        "-filter:v", f"minterpolate=fps={target_fps}:mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1",
        "-c:v", "libx264", "-preset", "slow", "-crf", "16",
        "-c:a", "copy",
        output_path,
    ]
    result = subprocess.run(cmd, capture_output=True)
    return result.returncode == 0


def color_grade(input_path: str, output_path: str, preset: str = "cinema") -> bool:
    """
    Apply color grading with FFmpeg.
    preset: "cinema" | "vivid" | "cool" | "warm" | "neutral"
    """
    presets = {
        "cinema": (
            "eq=contrast=1.1:brightness=0.02:saturation=0.9,"
            "curves=r='0/0 0.5/0.48 1/1':g='0/0 0.5/0.5 1/1':b='0/0 0.5/0.52 1/1',"
            "unsharp=5:5:0.8:3:3:0.0"
        ),
        "vivid": (
            "eq=contrast=1.15:brightness=0.03:saturation=1.3,"
            "unsharp=5:5:1.0:3:3:0.0"
        ),
        "cool": (
            "eq=contrast=1.05:saturation=0.95,"
            "curves=b='0/0.05 1/1',"
            "unsharp=5:5:0.6:3:3:0.0"
        ),
        "warm": (
            "eq=contrast=1.05:saturation=1.1,"
            "curves=r='0/0.05 1/1':b='0/0 1/0.95',"
            "unsharp=5:5:0.6:3:3:0.0"
        ),
        "neutral": "unsharp=5:5:0.5:3:3:0.0",
    }

    vf = presets.get(preset, presets["cinema"])
    cmd = [
        "ffmpeg", "-y", "-i", input_path,
        "-vf", vf,
        "-c:v", "libx264", "-preset", "slow", "-crf", "15",
        "-c:a", "copy",
        output_path,
    ]
    result = subprocess.run(cmd, capture_output=True)
    return result.returncode == 0


# ─── Full enhancement pipeline ────────────────────────────────────────────────

def enhance_video(
    input_path: str,
    output_path: str,
    upscale: bool = True,
    face_enhance: bool = True,
    interpolate: bool = True,
    target_fps: int = 60,
    color_preset: str = "cinema",
    progress_cb: Callable[[int], None] | None = None,
):
    """
    Full post-processing pipeline on a generated video.
    Applies: upscaling → face enhancement → interpolation → color grading.
    """
    import shutil

    tmp = Path(input_path).parent
    current = input_path
    step = 0

    def _next(msg, pct):
        nonlocal step
        step += 1
        if progress_cb:
            progress_cb(pct)

    # Step 1 — frame-by-frame upscaling via Real-ESRGAN
    if upscale:
        _next("Upscaling frames…", 10)
        upscaled = str(tmp / "up.mp4")
        ok = _upscale_video_frames(current, upscaled, face_enhance=face_enhance)
        if ok:
            current = upscaled
            _next("Frames upscalées", 50)

    # Step 2 — frame interpolation
    if interpolate:
        _next("Interpolation frames…", 55)
        interp = str(tmp / "interp.mp4")
        if interpolate_fps(current, interp, target_fps):
            current = interp
        _next("Interpolation terminée", 75)

    # Step 3 — color grading
    _next("Color grading…", 80)
    graded = str(tmp / "graded.mp4")
    if color_grade(current, graded, color_preset):
        current = graded
    _next("Color grading terminé", 95)

    shutil.copy(current, output_path)
    if progress_cb:
        progress_cb(100)


def _upscale_video_frames(
    input_video: str,
    output_video: str,
    face_enhance: bool = True,
    scale: int = 2,
) -> bool:
    """Extract frames, upscale each, re-encode."""
    try:
        import cv2
        import numpy as np

        # Check if Real-ESRGAN is available
        try:
            from basicsr.archs.rrdbnet_arch import RRDBNet
            from realesrgan import RealESRGANer
            realesrgan_ok = True
        except ImportError:
            realesrgan_ok = False

        try:
            from gfpgan import GFPGANer
            gfpgan_ok = True and face_enhance
        except ImportError:
            gfpgan_ok = False

        if not realesrgan_ok and not gfpgan_ok:
            return False

        cap = cv2.VideoCapture(input_video)
        fps = cap.get(cv2.CAP_PROP_FPS)
        w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))

        # Init models
        if realesrgan_ok:
            model_path = Path(__file__).parent.parent / "models" / "RealESRGAN_x4plus.pth"
            if model_path.exists():
                rn_model = RRDBNet(num_in_ch=3, num_out_ch=3, num_feat=64, num_block=23, num_grow_ch=32, scale=4)
                upsampler = RealESRGANer(
                    scale=scale, model_path=str(model_path), model=rn_model,
                    tile=512, tile_pad=10, pre_pad=0, half=True,
                )
            else:
                upsampler = None
        else:
            upsampler = None

        if gfpgan_ok:
            gfp_path = Path(__file__).parent.parent / "models" / "GFPGANv1.4.pth"
            if gfp_path.exists():
                restorer = GFPGANer(model_path=str(gfp_path), upscale=1, arch="clean", channel_multiplier=2)
            else:
                restorer = None
        else:
            restorer = None

        if upsampler is None and restorer is None:
            return False

        new_w = w * scale if upsampler else w
        new_h = h * scale if upsampler else h

        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        tmp_out = str(Path(input_video).parent / "_frames_tmp.mp4")
        out = cv2.VideoWriter(tmp_out, fourcc, fps, (new_w, new_h))

        while True:
            ret, frame = cap.read()
            if not ret:
                break

            if restorer is not None:
                _, _, frame = restorer.enhance(frame, has_aligned=False, only_center_face=False)

            if upsampler is not None:
                frame, _ = upsampler.enhance(frame, outscale=scale)

            out.write(frame)

        cap.release()
        out.release()

        # Re-mux with original audio
        cmd = [
            "ffmpeg", "-y",
            "-i", tmp_out,
            "-i", input_video,
            "-map", "0:v", "-map", "1:a?",
            "-c:v", "libx264", "-preset", "fast", "-crf", "15",
            "-c:a", "copy",
            output_video,
        ]
        result = subprocess.run(cmd, capture_output=True)
        Path(tmp_out).unlink(missing_ok=True)
        return result.returncode == 0

    except Exception:
        return False
