#!/usr/bin/env python3
"""Generate Android launcher icons and splash screens for NEPSE Pulse.

The only artwork source is the repository's existing, unmodified
``nepse-pulse-exact-icon.png``. This script never draws or substitutes a logo:
it resizes that exact bitmap and gives it the same rounded-square mask the
artwork already has, then places it on the existing dark navy background.

Usage:
    python3 scripts/generate_android_branding.py [res-dir ...]

With no arguments it updates the Capacitor project (``android/``) and the
committed native container (``android-app/``). The committed XML in
``android-overlay/`` is copied over each target so future ``cap sync`` runs
cannot drop the native branding.
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "nepse-pulse-exact-icon.png"
OVERLAY = ROOT / "android-overlay" / "app" / "src" / "main" / "res"
DEFAULT_TARGETS = [
    ROOT / "android" / "app" / "src" / "main" / "res",
    ROOT / "android-app" / "app" / "src" / "main" / "res",
]

# Android launcher icon nominal size (48dp) and splash sizes in dp.
DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}
LAUNCHER_DP = 48
ADAPTIVE_FOREGROUND_DP = 108
SPLASH_LOGO_DP = 160
SPLASH_ICON_DP = 288

NAVY = (6, 16, 32, 255)
CORNER_RADIUS_RATIO = 0.22


def rounded(image: Image.Image) -> Image.Image:
    """Apply the artwork's own rounded-square shape with clean corners."""
    side = image.size[0]
    supersample = 4
    mask = Image.new("L", (side * supersample, side * supersample), 0)
    draw = ImageDraw.Draw(mask)
    radius = int(side * CORNER_RADIUS_RATIO * supersample)
    draw.rounded_rectangle(
        [(0, 0), (side * supersample - 1, side * supersample - 1)],
        radius=radius,
        fill=255,
    )
    mask = mask.resize((side, side), Image.LANCZOS)
    output = image.copy()
    output.putalpha(mask)
    return output


def square(image: Image.Image) -> Image.Image:
    side = max(image.size)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(image, ((side - image.width) // 2, (side - image.height) // 2))
    return canvas


def compose(canvas_px: int, artwork: Image.Image, ratio: float, background: tuple[int, int, int, int]) -> Image.Image:
    canvas = Image.new("RGBA", (canvas_px, canvas_px), background)
    inner = max(1, int(round(canvas_px * ratio)))
    scaled = artwork.resize((inner, inner), Image.LANCZOS)
    offset = (canvas_px - inner) // 2
    canvas.alpha_composite(scaled, (offset, offset))
    return canvas


def copy_overlay(target: Path) -> None:
    for path in OVERLAY.rglob("*"):
        if path.is_file():
            destination = target / path.relative_to(OVERLAY)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(path.read_bytes())


def update_target(target: Path, artwork: Image.Image) -> list[str]:
    written: list[str] = []
    target.mkdir(parents=True, exist_ok=True)
    # The Capacitor project uses appcompat/splash-screen themes, so its XML
    # overlay is applied here. The committed native container keeps its own
    # platform-theme XML and only needs the generated bitmaps.
    if (ROOT / "android-app") not in target.parents:
        copy_overlay(target)

    for density, scale in DENSITIES.items():
        launcher = int(round(LAUNCHER_DP * scale))
        legacy = artwork.resize((launcher, launcher), Image.LANCZOS)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            path = target / f"mipmap-{density}" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            legacy.save(path, optimize=True)
            written.append(str(path))

        foreground = compose(
            int(round(ADAPTIVE_FOREGROUND_DP * scale)), artwork, 0.72, (0, 0, 0, 0)
        )
        path = target / f"mipmap-{density}" / "ic_launcher_foreground.png"
        foreground.save(path, optimize=True)
        written.append(str(path))

        logo = compose(int(round(SPLASH_LOGO_DP * scale)), artwork, 0.90, (0, 0, 0, 0))
        path = target / f"drawable-{density}" / "np_splash_logo.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        logo.save(path, optimize=True)
        written.append(str(path))

        icon = compose(int(round(SPLASH_ICON_DP * scale)), artwork, 0.62, (0, 0, 0, 0))
        path = target / f"drawable-{density}" / "np_splash_icon.png"
        icon.save(path, optimize=True)
        written.append(str(path))

    # A density-independent fallback for tools that read drawable-nodpi.
    fallback = compose(SPLASH_LOGO_DP * 3, artwork, 0.90, (0, 0, 0, 0))
    path = target / "drawable-nodpi" / "np_splash_logo.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    fallback.save(path, optimize=True)
    written.append(str(path))
    return written


def main() -> int:
    if not SOURCE.exists():
        print(f"Missing source artwork: {SOURCE}", file=sys.stderr)
        return 1
    source = Image.open(SOURCE).convert("RGBA")
    artwork = rounded(square(source))

    targets = [Path(arg).resolve() for arg in sys.argv[1:]] or DEFAULT_TARGETS
    existing = [path for path in targets if path.parent.parent.exists() or path.exists()]
    if not existing:
        print("No Android resource directory found. Generate the Capacitor project first.", file=sys.stderr)
        return 1

    for target in existing:
        written = update_target(target, artwork)
        print(f"Branded {target} ({len(written)} image files) from {SOURCE.name}")
    print("Launcher icons, adaptive icons and Android 12/pre-12 splash screens are configured.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
