#!/usr/bin/env python3
"""Remove exact #00FF00 pixels from a PNG by making them transparent."""

from __future__ import annotations

import argparse
import binascii
import struct
import zlib
from pathlib import Path


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
TARGET_RGB = (0x00, 0xFF, 0x00)
DEFAULT_TOLERANCE = 24


def paeth_predictor(left: int, up: int, up_left: int) -> int:
    p = left + up - up_left
    pa = abs(p - left)
    pb = abs(p - up)
    pc = abs(p - up_left)
    if pa <= pb and pa <= pc:
        return left
    if pb <= pc:
        return up
    return up_left


def read_png(path: Path) -> tuple[dict[str, int], list[tuple[bytes, bytes]], bytes]:
    with path.open("rb") as f:
        if f.read(8) != PNG_SIGNATURE:
            raise ValueError(f"{path} is not a PNG file")

        ihdr: dict[str, int] | None = None
        chunks: list[tuple[bytes, bytes]] = []
        idat_parts: list[bytes] = []

        while True:
            header = f.read(8)
            if len(header) != 8:
                raise ValueError("Unexpected end of PNG")

            length, chunk_type = struct.unpack(">I4s", header)
            data = f.read(length)
            crc = f.read(4)
            if len(data) != length or len(crc) != 4:
                raise ValueError("Unexpected end of PNG chunk")

            if chunk_type == b"IHDR":
                width, height, bit_depth, color_type, compression, filter_method, interlace = struct.unpack(
                    ">IIBBBBB", data
                )
                ihdr = {
                    "width": width,
                    "height": height,
                    "bit_depth": bit_depth,
                    "color_type": color_type,
                    "compression": compression,
                    "filter_method": filter_method,
                    "interlace": interlace,
                }
            elif chunk_type == b"IDAT":
                idat_parts.append(data)
            elif chunk_type != b"IEND":
                chunks.append((chunk_type, data))

            if chunk_type == b"IEND":
                break

        if ihdr is None:
            raise ValueError("PNG is missing IHDR")
        return ihdr, chunks, zlib.decompress(b"".join(idat_parts))


def unfilter_rows(raw: bytes, width: int, height: int, bytes_per_pixel: int) -> list[bytearray]:
    stride = width * bytes_per_pixel
    rows: list[bytearray] = []
    pos = 0

    for y in range(height):
        filter_type = raw[pos]
        pos += 1
        row = bytearray(raw[pos : pos + stride])
        pos += stride
        prev = rows[y - 1] if y else bytearray(stride)

        for x in range(stride):
            left = row[x - bytes_per_pixel] if x >= bytes_per_pixel else 0
            up = prev[x]
            up_left = prev[x - bytes_per_pixel] if x >= bytes_per_pixel else 0

            if filter_type == 0:
                value = row[x]
            elif filter_type == 1:
                value = row[x] + left
            elif filter_type == 2:
                value = row[x] + up
            elif filter_type == 3:
                value = row[x] + ((left + up) // 2)
            elif filter_type == 4:
                value = row[x] + paeth_predictor(left, up, up_left)
            else:
                raise ValueError(f"Unsupported PNG filter type: {filter_type}")
            row[x] = value & 0xFF

        rows.append(row)

    return rows


def filter_rows(rows: list[bytearray]) -> bytes:
    # Filter type 0 keeps the writer small and universally compatible.
    return b"".join(b"\x00" + bytes(row) for row in rows)


def make_chunk(chunk_type: bytes, data: bytes) -> bytes:
    crc = binascii.crc32(chunk_type + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + chunk_type + data + struct.pack(">I", crc)


def write_png(path: Path, ihdr: dict[str, int], chunks: list[tuple[bytes, bytes]], rows: list[bytearray]) -> None:
    ihdr_data = struct.pack(
        ">IIBBBBB",
        ihdr["width"],
        ihdr["height"],
        ihdr["bit_depth"],
        ihdr["color_type"],
        ihdr["compression"],
        ihdr["filter_method"],
        ihdr["interlace"],
    )
    compressed = zlib.compress(filter_rows(rows), level=9)

    with path.open("wb") as f:
        f.write(PNG_SIGNATURE)
        f.write(make_chunk(b"IHDR", ihdr_data))
        for chunk_type, data in chunks:
            if chunk_type not in {b"tRNS"}:
                f.write(make_chunk(chunk_type, data))
        f.write(make_chunk(b"IDAT", compressed))
        f.write(make_chunk(b"IEND", b""))


def is_target_green(red: int, green: int, blue: int, tolerance: int) -> bool:
    target_red, target_green, target_blue = TARGET_RGB
    return (
        abs(red - target_red) <= tolerance
        and abs(green - target_green) <= tolerance
        and abs(blue - target_blue) <= tolerance
    )


def remove_green(input_path: Path, output_path: Path, tolerance: int) -> int:
    ihdr, chunks, raw = read_png(input_path)

    if ihdr["bit_depth"] != 8 or ihdr["interlace"] != 0:
        raise ValueError("This tool supports 8-bit, non-interlaced PNG files")
    if ihdr["color_type"] == 6:
        bytes_per_pixel = 4
    elif ihdr["color_type"] == 2:
        bytes_per_pixel = 3
        ihdr["color_type"] = 6
    else:
        raise ValueError("This tool supports RGB and RGBA PNG files")

    rows = unfilter_rows(raw, ihdr["width"], ihdr["height"], bytes_per_pixel)
    removed = 0

    if bytes_per_pixel == 3:
        rgba_rows: list[bytearray] = []
        for row in rows:
            out = bytearray()
            for i in range(0, len(row), 3):
                r, g, b = row[i], row[i + 1], row[i + 2]
                alpha = 0 if is_target_green(r, g, b, tolerance) else 255
                removed += alpha == 0
                out.extend((r, g, b, alpha))
            rgba_rows.append(out)
        rows = rgba_rows
    else:
        for row in rows:
            for i in range(0, len(row), 4):
                if is_target_green(row[i], row[i + 1], row[i + 2], tolerance):
                    row[i + 3] = 0
                    removed += 1

    write_png(output_path, ihdr, chunks, rows)
    return removed


def main() -> None:
    parser = argparse.ArgumentParser(description="Make #00FF00 pixels transparent in a PNG.")
    parser.add_argument("input", nargs="?", default="import.png", help="Input PNG path. Default: import.png")
    parser.add_argument(
        "output",
        nargs="?",
        default="import_no_00ff00.png",
        help="Output PNG path. Default: import_no_00ff00.png",
    )
    parser.add_argument(
        "--tolerance",
        type=int,
        default=DEFAULT_TOLERANCE,
        help=f"Per-channel color tolerance. Use 0 for exact #00FF00 only. Default: {DEFAULT_TOLERANCE}",
    )
    args = parser.parse_args()
    if not 0 <= args.tolerance <= 255:
        raise SystemExit("--tolerance must be between 0 and 255")

    input_path = Path(args.input)
    output_path = Path(args.output)
    removed = remove_green(input_path, output_path, args.tolerance)
    print(f"Done: made {removed} #00FF00-like pixels transparent -> {output_path}")


if __name__ == "__main__":
    main()
