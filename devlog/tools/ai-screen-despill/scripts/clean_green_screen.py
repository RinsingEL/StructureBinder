#!/usr/bin/env python3
"""Red/green/blue-screen cleanup with alpha generation and despill."""

from __future__ import annotations

import argparse

import cv2
import numpy as np


def read_image(path: str) -> np.ndarray:
    data = np.fromfile(path, dtype=np.uint8)
    img = cv2.imdecode(data, cv2.IMREAD_COLOR)
    if img is None:
        raise FileNotFoundError(path)
    return img


def write_png(path: str, img: np.ndarray) -> None:
    ok, encoded = cv2.imencode(".png", img)
    if not ok:
        raise OSError(f"Could not encode {path}")
    encoded.tofile(path)


def clean_screen(
    img_path: str,
    output_path: str,
    alpha_strength: float = 2.0,
    screen: str = "blue",
) -> None:
    img = read_image(img_path)

    img = img.astype(np.float32) / 255.0
    b, g, r = img[:, :, 0], img[:, :, 1], img[:, :, 2]

    if screen == "blue":
        key = b
        gate = (r + g) * 0.5
        b_clean = np.where(key > gate, gate, b)
        g_clean = g
        r_clean = r
    elif screen == "red":
        key = r
        gate = (g + b) * 0.5
        b_clean = b
        g_clean = g
        r_clean = np.where(key > gate, gate, r)
    elif screen == "green":
        key = g
        gate = (r + b) * 0.5
        b_clean = b
        g_clean = np.where(key > gate, gate, g)
        r_clean = r
    else:
        raise ValueError("screen must be 'red', 'green', or 'blue'")

    # Stronger screen color becomes more transparent. Increase alpha_strength for a harder key.
    alpha = 1.0 - (key - gate) * alpha_strength
    alpha = np.clip(alpha, 0.0, 1.0)

    img_clean = cv2.merge([b_clean, g_clean, r_clean, alpha])
    img_clean = (img_clean * 255).astype(np.uint8)

    write_png(output_path, img_clean)


def clean_green_screen(img_path: str, output_path: str, alpha_strength: float = 2.0) -> None:
    clean_screen(img_path, output_path, alpha_strength, screen="green")


def main() -> None:
    parser = argparse.ArgumentParser(description="Clean a red/green/blue-screen image into a transparent PNG.")
    parser.add_argument("input", nargs="?", default="import.png", help="Input image. Default: import.png")
    parser.add_argument("output", nargs="?", default="import_despill.png", help="Output PNG. Default: import_despill.png")
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

    clean_screen(args.input, args.output, args.alpha_strength, args.screen)
    print(f"Done: wrote {args.output}")


if __name__ == "__main__":
    main()
