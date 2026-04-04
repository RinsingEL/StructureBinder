#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
grid_overlay.py
给图片叠加透明网格（细网格/粗网格/刻度），不修改原图内容，只做overlay合成。

用法示例：
1) 单张图输出：
   python grid_overlay.py in.png -o out.png --minor 4 --major 32 --label-step 32

2) 批量处理一个文件夹（只处理png）：
   python grid_overlay.py ./maps -O ./maps_grid --minor 4 --major 32 --label-step 32

3) 让“坐标格”更小（比如 1格=4像素）：
   --minor 4 --major 32 --label-step 32
   （表示：细网格每4px，粗网格每32px，刻度每32px）
"""

import argparse
import os
from pathlib import Path
from typing import Iterable, Optional, Tuple

from PIL import Image, ImageDraw, ImageFont


def parse_rgba(s: str) -> Tuple[int, int, int, int]:
    """
    解析形如 '255,255,255,40' 的RGBA字符串
    """
    parts = [p.strip() for p in s.split(",")]
    if len(parts) != 4:
        raise ValueError(f"RGBA必须是4个数，例如 255,255,255,40；你给的是: {s}")
    vals = tuple(int(x) for x in parts)
    if any(v < 0 or v > 255 for v in vals):
        raise ValueError(f"RGBA每个值必须在0~255；你给的是: {s}")
    return vals  # type: ignore


def iter_images(input_path: Path, recursive: bool = False) -> Iterable[Path]:
    if input_path.is_file():
        yield input_path
        return

    pattern = "**/*.png" if recursive else "*.png"
    for p in sorted(input_path.glob(pattern)):
        if p.is_file():
            yield p


def load_font(font_size: int) -> ImageFont.ImageFont:
    """
    尝试加载一个常见字体；失败则用默认字体（仍可用但可能偏小）
    """
    # 你也可以把你自己的字体文件路径传进来扩展，这里先给一个稳健的默认策略
    try:
        return ImageFont.truetype("arial.ttf", font_size)
    except Exception:
        try:
            return ImageFont.truetype("DejaVuSans.ttf", font_size)
        except Exception:
            return ImageFont.load_default()


def overlay_grid(
    img: Image.Image,
    minor_step: int,
    major_step: int,
    minor_rgba: Tuple[int, int, int, int],
    major_rgba: Tuple[int, int, int, int],
    label_step: int,
    label_rgba: Tuple[int, int, int, int],
    label_padding: int,
    font_size: int,
    draw_border: bool,
    border_rgba: Tuple[int, int, int, int],
    legend_text: Optional[str],
    legend_rgba: Tuple[int, int, int, int],
    legend_padding: int,
) -> Image.Image:
    base = img.convert("RGBA")
    w, h = base.size

    overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)

    # 可选边框
    if draw_border:
        draw.rectangle([0, 0, w - 1, h - 1], outline=border_rgba)

    # 画竖线
    if minor_step > 0:
        for x in range(0, w, minor_step):
            color = major_rgba if (major_step > 0 and x % major_step == 0) else minor_rgba
            draw.line([(x, 0), (x, h - 1)], fill=color)

    # 画横线
    if minor_step > 0:
        for y in range(0, h, minor_step):
            color = major_rgba if (major_step > 0 and y % major_step == 0) else minor_rgba
            draw.line([(0, y), (w - 1, y)], fill=color)

    # 刻度文字
    if label_step > 0:
        font = load_font(font_size)

        # 顶部x刻度
        for x in range(0, w, label_step):
            # 小背景可选：这里简单画描边效果（同色文字叠两次略偏移）以提高可读性
            text = str(x)
            tx, ty = x + label_padding, label_padding
            draw.text((tx + 1, ty + 1), text, fill=(0, 0, 0, min(255, label_rgba[3])), font=font)
            draw.text((tx, ty), text, fill=label_rgba, font=font)

        # 左侧y刻度
        for y in range(0, h, label_step):
            text = str(y)
            tx, ty = label_padding, y + label_padding
            draw.text((tx + 1, ty + 1), text, fill=(0, 0, 0, min(255, label_rgba[3])), font=font)
            draw.text((tx, ty), text, fill=label_rgba, font=font)

    # 右下角legend（协议声明）
    if legend_text:
        font = load_font(font_size)
        # 计算大致文本尺寸
        bbox = draw.textbbox((0, 0), legend_text, font=font)
        tw = bbox[2] - bbox[0]
        th = bbox[3] - bbox[1]
        x = max(0, w - tw - legend_padding)
        y = max(0, h - th - legend_padding)
        # 画轻微阴影提高可读性
        draw.text((x + 1, y + 1), legend_text, fill=(0, 0, 0, min(255, legend_rgba[3])), font=font)
        draw.text((x, y), legend_text, fill=legend_rgba, font=font)

    return Image.alpha_composite(base, overlay)


def ensure_out_path(
    in_file: Path,
    out_file: Optional[Path],
    out_dir: Optional[Path],
) -> Path:
    if out_file:
        return out_file

    if out_dir:
        out_dir.mkdir(parents=True, exist_ok=True)
        return out_dir / in_file.name

    # 默认：同目录加后缀
    return in_file.with_name(in_file.stem + "_grid" + in_file.suffix)


def main():
    ap = argparse.ArgumentParser(description="Overlay grid + ticks onto PNG images (non-destructive).")
    ap.add_argument("input", help="输入：单个png文件 或 目录（目录会批量处理png）")
    ap.add_argument("-o", "--output", help="输出文件路径（仅当input是单文件时使用）")
    ap.add_argument("-O", "--out-dir", help="输出目录（用于批处理；或单文件也可）")
    ap.add_argument("--recursive", action="store_true", help="目录批处理时递归子目录")

    ap.add_argument("--minor", type=int, default=4, help="细网格间距（像素）。建议4或8；不建议1（太密）")
    ap.add_argument("--major", type=int, default=32, help="粗网格间距（像素）。建议32或64")
    ap.add_argument("--label-step", type=int, default=32, help="刻度标注间距（像素）。建议与major一致")

    ap.add_argument("--minor-color", default="255,255,255,40", help="细网格RGBA，例如 255,255,255,40")
    ap.add_argument("--major-color", default="255,255,255,120", help="粗网格RGBA，例如 255,255,255,120")
    ap.add_argument("--label-color", default="255,255,255,200", help="刻度文字RGBA，例如 255,255,255,200")

    ap.add_argument("--font-size", type=int, default=12, help="刻度/legend字体大小")
    ap.add_argument("--label-padding", type=int, default=2, help="刻度距离边缘的内边距（像素）")

    ap.add_argument("--border", action="store_true", help="是否画一圈边框")
    ap.add_argument("--border-color", default="255,255,255,160", help="边框RGBA，例如 255,255,255,160")

    ap.add_argument(
        "--legend",
        default=None,
        help='右下角说明文字，例如: "Grid:1cell=4px  Origin:(0,0) TL  x→ y↓"',
    )
    ap.add_argument("--legend-color", default="255,255,255,200", help="legend文字RGBA")
    ap.add_argument("--legend-padding", type=int, default=6, help="legend距离右下角内边距（像素）")

    args = ap.parse_args()

    input_path = Path(args.input).expanduser().resolve()
    out_file = Path(args.output).expanduser().resolve() if args.output else None
    out_dir = Path(args.out_dir).expanduser().resolve() if args.out_dir else None

    minor_rgba = parse_rgba(args.minor_color)
    major_rgba = parse_rgba(args.major_color)
    label_rgba = parse_rgba(args.label_color)
    border_rgba = parse_rgba(args.border_color)
    legend_rgba = parse_rgba(args.legend_color)

    # 批量 or 单文件
    images = list(iter_images(input_path, recursive=args.recursive))
    if not images:
        raise SystemExit(f"没找到png：{input_path}")

    # 单文件时 -o 合法；多文件时忽略 -o
    if len(images) > 1 and out_file:
        print("提示：input是目录/多文件，-o 会被忽略，请使用 -O 指定输出目录。")
        out_file = None

    for in_file in images:
        img = Image.open(in_file)
        result = overlay_grid(
            img=img,
            minor_step=args.minor,
            major_step=args.major,
            minor_rgba=minor_rgba,
            major_rgba=major_rgba,
            label_step=args.label_step,
            label_rgba=label_rgba,
            label_padding=args.label_padding,
            font_size=args.font_size,
            draw_border=args.border,
            border_rgba=border_rgba,
            legend_text=args.legend,
            legend_rgba=legend_rgba,
            legend_padding=args.legend_padding,
        )

        target = ensure_out_path(in_file, out_file, out_dir)
        target.parent.mkdir(parents=True, exist_ok=True)
        result.save(target)
        print(f"[OK] {in_file.name} -> {target}")

    print("完成。")


if __name__ == "__main__":
    main()