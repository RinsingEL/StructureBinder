#!/usr/bin/env python3
"""Quick 6-line D4 audition test for Fish Audio TTS (zero external dependencies)."""
import argparse
import os
import re
import time
from pathlib import Path

from fish_common import (
    BASE_DIR,
    call_fish_tts,
    ensure_utf8_stdio,
    load_ref_cache,
    load_wav_mono,
    normalize_rms,
    pack_msgpack,
    write_wav_mono,
)

LINES = [
    ("fish_01_deepseek.wav", "deepseek", "一栋十六张床，四栋就是六十四，住五十六个人，还留了点余量，这个数算得挺舒服。"),
    ("fish_02_glm.wav", "glm", "四栋楼门全朝里开、围出一个院子，出门几步就是食堂……先不说 AI 规划得怎么样，我自己看着都想进去住。"),
    ("fish_03_claude.wav", "claude", "然后，程序算完了……只落下了一栋。"),
    ("fish_04_gemini.wav", "gemini", "你们俩刚夸完数算得舒服、还想搬进去住，结果四栋一下没了三栋，五十六个学生只剩十六张床了！ [chuckle] 另外那三栋是怎么没放上去的呀？"),
    ("fish_05_astra.wav", "astra", "这里得分清「想放四栋」和「实际算出四栋」。AI 填的四栋是请求，程序这次返回的位置却只有一个。刚才六十四张床只是计划，得回头检查排布。"),
    ("fish_06_glm_report.wav", "glm", "关键信息没传到我手上，我干脆停下来交了份阻塞报告。所以那份测试问题清单里，速度和失败恢复这两行，都有我的贡献。"),
]


def stitch_reel(names: list[str], out_path: Path) -> float:
    """Normalize and concatenate the 5 D4 dialogue lines with 0.28s pauses."""
    chunks: list[float] = []
    sr_ref = None
    for idx, fn in enumerate(names):
        sr, x = load_wav_mono(BASE_DIR / fn)
        sr_ref = sr
        chunks.extend(normalize_rms(x))
        if idx < len(names) - 1:
            chunks.extend([0.0] * int(0.28 * sr))
    if sr_ref is None:
        raise ValueError("No audio clips to stitch")
    write_wav_mono(out_path, sr_ref, chunks)
    return len(chunks) / sr_ref


def main() -> None:
    ensure_utf8_stdio()
    ap = argparse.ArgumentParser(description="Test Fish Audio TTS on D4 5-speaker lines.")
    ap.add_argument("--model", default=os.environ.get("FISH_MODEL", "s2.1-pro-free"))
    ap.add_argument("--dry-run", action="store_true", help="Verify refs and payload without API calls")
    ap.add_argument("--delay", type=float, default=0.4, help="Seconds to pause between API calls")
    args = ap.parse_args()

    ref_cache = load_ref_cache(BASE_DIR)

    if args.dry_run:
        sample = pack_msgpack({
            "text": LINES[0][2],
            "references": [ref_cache["deepseek"]],
            "format": "wav",
        })
        print(f"Dry-run OK: model={args.model}, refs={len(ref_cache)}, sample_payload={len(sample)} bytes")
        return

    api_key = os.environ.get("FISH_API_KEY", "").strip()
    if not api_key:
        raise SystemExit(
            "Missing FISH_API_KEY env var!\n"
            "  Windows PowerShell: $env:FISH_API_KEY='your_key'\n"
            "  macOS / Linux:      export FISH_API_KEY='your_key'"
        )

    for fn, spk, txt in LINES:
        t0 = time.time()
        audio = call_fish_tts(api_key, args.model, txt, ref_cache[spk])
        out_wav = BASE_DIR / fn
        out_wav.write_bytes(audio)
        sr, x = load_wav_mono(out_wav)
        dur = len(x) / sr
        clean = re.sub(r"\[[^\]]+\]|[\s，。、！？……—“”「」]", "", txt)
        print(
            f"OK {fn:24s} dur={dur:5.2f}s speed={len(clean)/max(dur, 0.1):4.2f}字/s ({time.time()-t0:.1f}s)",
            flush=True,
        )
        if args.delay > 0:
            time.sleep(args.delay)

    reel_dur = stitch_reel([x[0] for x in LINES[:5]], BASE_DIR / "fish_d4_reel.wav")
    print(f"Stitched fish_d4_reel.wav ({reel_dur:.2f}s)!")


if __name__ == "__main__":
    main()
