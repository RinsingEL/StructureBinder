#!/usr/bin/env python3
"""Zero-dependency shared helpers for Fish Audio TTS & WAV handling."""
import array
import math
import os
import struct
import sys
import time
import urllib.error
import urllib.request
import wave
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent

REF_BASIC = "大家好，欢迎来参观我们的社团。今天，我们一起看看这座城市吧。"
# User-confirmed tentative set: original Claude + current GLM (2026-11-17).
REFERENCE_SET_DIR = "references/暂定_20261117"
REFS = {
    "deepseek": (f"{REFERENCE_SET_DIR}/DeepSeek.wav", REF_BASIC),
    "glm": (f"{REFERENCE_SET_DIR}/GLM.wav", REF_BASIC),
    "claude": (f"{REFERENCE_SET_DIR}/Claude.wav", REF_BASIC),
    "gemini": (f"{REFERENCE_SET_DIR}/Gemini.wav", REF_BASIC),
    "astra": (
        f"{REFERENCE_SET_DIR}/Astra.wav",
        "大家好，我是 Astra。平时负责把大家的点子写进代码里，再仔细检查一下，有没有哪里不小心出了小差错。",
    ),
}


def ensure_utf8_stdio() -> None:
    """Avoid UnicodeEncodeError on Windows consoles using legacy code pages."""
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except Exception:
                pass


def pack_msgpack(obj) -> bytes:
    """Minimal zero-dependency MessagePack serializer."""
    if obj is None:
        return b"\xc0"
    if isinstance(obj, bool):
        return b"\xc3" if obj else b"\xc2"
    if isinstance(obj, int):
        if 0 <= obj <= 0x7F:
            return bytes([obj])
        if -32 <= obj < 0:
            return struct.pack("b", obj)
        return b"\xd3" + struct.pack(">q", obj)
    if isinstance(obj, float):
        return b"\xcb" + struct.pack(">d", obj)
    if isinstance(obj, (bytes, bytearray)):
        n = len(obj)
        if n < 256:
            return b"\xc4" + bytes([n]) + bytes(obj)
        if n < 65536:
            return b"\xc5" + struct.pack(">H", n) + bytes(obj)
        return b"\xc6" + struct.pack(">I", n) + bytes(obj)
    if isinstance(obj, str):
        b = obj.encode("utf-8")
        n = len(b)
        if n < 32:
            return bytes([0xA0 | n]) + b
        if n < 256:
            return b"\xd9" + bytes([n]) + b
        if n < 65536:
            return b"\xda" + struct.pack(">H", n) + b
        return b"\xdb" + struct.pack(">I", n) + b
    if isinstance(obj, (list, tuple)):
        n = len(obj)
        hdr = bytes([0x90 | n]) if n < 16 else (b"\xdc" + struct.pack(">H", n))
        return hdr + b"".join(pack_msgpack(x) for x in obj)
    if isinstance(obj, dict):
        n = len(obj)
        hdr = bytes([0x80 | n]) if n < 16 else (b"\xde" + struct.pack(">H", n))
        return hdr + b"".join(pack_msgpack(k) + pack_msgpack(v) for k, v in obj.items())
    raise TypeError(f"Unsupported type for msgpack: {type(obj)}")


def load_wav_mono(path: Path) -> tuple[int, list[float]]:
    """Read PCM 16/24-bit or float32 WAV into normalized mono float samples."""
    with wave.open(str(path), "rb") as w:
        sr, ch, sw, n = w.getframerate(), w.getnchannels(), w.getsampwidth(), w.getnframes()
        raw = w.readframes(n)
    if sw == 2:
        arr = array.array("h")
        arr.frombytes(raw)
        if sys.byteorder == "big":
            arr.byteswap()
        samples = [v / 32768.0 for v in arr]
    elif sw == 3:
        samples = [
            int.from_bytes(raw[i : i + 3], "little", signed=True) / 8388608.0
            for i in range(0, len(raw), 3)
        ]
    elif sw == 4:
        samples = [v[0] for v in struct.iter_unpack("<f", raw)]
    else:
        raise ValueError(f"Unsupported sample width {sw} bytes in {path}")
    if ch > 1:
        samples = [
            sum(samples[i : i + ch]) / ch for i in range(0, len(samples), ch)
        ]
    return sr, samples


def write_wav_mono(path: Path, sr: int, samples: list[float]) -> None:
    """Write mono float [-1, 1] samples to a 16-bit PCM WAV file."""
    path.parent.mkdir(parents=True, exist_ok=True)
    arr = array.array(
        "h",
        (int(max(-0.98, min(0.98, s)) * 32767.0) for s in samples),
    )
    if sys.byteorder == "big":
        arr.byteswap()
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(arr.tobytes())


def normalize_rms(samples: list[float], target_rms: float = 0.125) -> list[float]:
    """Normalize active speech frames (|s| > 0.015) to target RMS."""
    active = [s for s in samples if abs(s) > 0.015]
    if not active:
        return [max(-0.98, min(0.98, s)) for s in samples]
    rms = math.sqrt(sum(s * s for s in active) / len(active))
    gain = target_rms / max(rms, 1e-6)
    return [max(-0.98, min(0.98, s * gain)) for s in samples]


def load_ref_cache(base_dir: Path = BASE_DIR) -> dict[str, dict]:
    """Load the 5 locked synthetic reference WAVs and transcripts."""
    cache = {}
    for spk, (rel_name, ref_txt) in REFS.items():
        wav_path = base_dir / rel_name
        if not wav_path.is_file():
            raise FileNotFoundError(f"Missing reference WAV: {wav_path}")
        cache[spk] = {"audio": wav_path.read_bytes(), "text": ref_txt}
    return cache


def call_fish_tts(
    api_key: str,
    model: str,
    text: str,
    ref_entry: dict,
    retries: int = 3,
) -> bytes:
    """Call Fish Audio POST /v1/tts with exponential backoff on transient errors."""
    payload = pack_msgpack({
        "text": text,
        "references": [ref_entry],
        "format": "wav",
    })
    for attempt in range(1, retries + 1):
        req = urllib.request.Request(
            "https://api.fish.audio/v1/tts",
            data=payload,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/msgpack",
                "model": model,
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                return resp.read()
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", errors="replace")
            if e.code in (429, 500, 502, 503, 504) and attempt < retries:
                wait_s = 2.0 * attempt
                print(f"  [retry {attempt}/{retries}] HTTP {e.code}, sleeping {wait_s:.0f}s...", flush=True)
                time.sleep(wait_s)
                continue
            raise SystemExit(f"HTTP {e.code}: {body}")
        except urllib.error.URLError as e:
            if attempt < retries:
                wait_s = 2.0 * attempt
                print(f"  [retry {attempt}/{retries}] Network error ({e.reason}), sleeping {wait_s:.0f}s...", flush=True)
                time.sleep(wait_s)
                continue
            raise SystemExit(f"Network error: {e}")
    raise SystemExit("Unreachable retry loop exit")
