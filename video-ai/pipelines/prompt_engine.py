"""
Smart prompt enhancement — automatically boosts quality of any prompt.
Adds cinematic quality tags, negative prompts, and style enhancers.
"""

QUALITY_SUFFIX = (
    "8K UHD, photorealistic, ultra-detailed, sharp focus, "
    "professional photography, cinematic lighting, high dynamic range, "
    "perfect exposure, film grain, masterpiece"
)

NEGATIVE_BASE = (
    "blurry, out of focus, low quality, low resolution, pixelated, "
    "jpeg artifacts, watermark, text, logo, oversaturated, "
    "deformed, distorted, ugly, bad anatomy, extra limbs, "
    "duplicate, cropped, worst quality, bad quality, "
    "cartoon, anime, drawing, painting, illustration, "
    "disfigured, mutated, poorly drawn face, mutation"
)

STYLE_PRESETS = {
    "cinematic": (
        "cinematic film still, anamorphic lens, shallow depth of field, "
        "dramatic lighting, 35mm film, movie quality, director's cut"
    ),
    "portrait": (
        "professional portrait photography, studio lighting, "
        "85mm lens, bokeh background, perfect skin texture, "
        "catch light in eyes, natural expression"
    ),
    "realistic": (
        "hyperrealistic, photorealistic, RAW photo, "
        "Canon EOS R5, natural lighting, skin pores visible, "
        "subsurface scattering, physically based rendering"
    ),
    "outdoor": (
        "golden hour lighting, natural environment, "
        "atmospheric perspective, volumetric light rays, "
        "photojournalism, Leica M11"
    ),
}


def enhance_prompt(prompt: str, style: str = "cinematic") -> str:
    """Append quality boosters to any user prompt."""
    style_tags = STYLE_PRESETS.get(style, STYLE_PRESETS["cinematic"])
    return f"{prompt}, {style_tags}, {QUALITY_SUFFIX}"


def get_negative_prompt(extra: str = "") -> str:
    if extra:
        return f"{NEGATIVE_BASE}, {extra}"
    return NEGATIVE_BASE


def build_wan_prompt(user_prompt: str) -> tuple[str, str]:
    """Return (positive, negative) for Wan2.1."""
    positive = enhance_prompt(user_prompt, "cinematic")
    negative = get_negative_prompt(
        "static, no movement, frozen, still image, stop motion"
    )
    return positive, negative


def build_flux_prompt(user_prompt: str, style: str = "realistic") -> tuple[str, str]:
    """Return (positive, negative) for FLUX.1."""
    positive = enhance_prompt(user_prompt, style)
    negative = get_negative_prompt()
    return positive, negative
