#!/usr/bin/env python3
"""Batch clean red/green/blue-screen images in a folder."""

from __future__ import annotations

import argparse
from pathlib import Path

from clean_green_screen import clean_screen


IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".bmp", ".webp"}


def output_path_for(image_path: Path, output_dir: Path) -> Path:
    return output_dir / f"{image_path.stem}_despill.png"


def batch_clean(folder: Path, alpha_strength: float, screen: str) -> int:
    if not folder.is_dir():
        raise NotADirectoryError(folder)

    images = [
        path
        for path in sorted(folder.iterdir())
        if path.is_file()
        and path.suffix.lower() in IMAGE_EXTENSIONS
        and not path.stem.endswith("_despill")
    ]

    for image_path in images:
        output_path = output_path_for(image_path, folder)
        clean_screen(str(image_path), str(output_path), alpha_strength, screen)
        print(f"{image_path.name} -> {output_path.name}")

    return len(images)


def main() -> None:
    parser = argparse.ArgumentParser(description="Batch clean red/green/blue-screen images in a folder.")
    parser.add_argument("folder", nargs="?", default="pics", help="Input folder. Default: pics")
    parser.add_argument(
        "--screen",
        choices=("blue", "green", "red"),
        default="blue",
        help="Screen color to remove. Default: blue",
    )
    parser.add_argument(
        "--alpha-strength",
        type=float,
        default=2.0,
        help="Higher values make screen-color areas more transparent. Default: 2.0",
    )
    args = parser.parse_args()

    count = batch_clean(Path(args.folder), args.alpha_strength, args.screen)
    print(f"Done: processed {count} image(s).")


if __name__ == "__main__":
    main()
