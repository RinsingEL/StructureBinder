#!/usr/bin/env python3
"""Batch Fish Audio TTS generator for all 5 Devlog timelines (zero external dependencies)."""
import argparse
import array
import json
import os
import re
import sys
import time
from pathlib import Path

from fish_common import (
    BASE_DIR,
    call_fish_tts,
    ensure_utf8_stdio,
    load_ref_cache,
    load_wav_mono,
    normalize_rms,
)

ACTS = ["开场", "第二段", "第三段", "第四段", "第五段"]
SKIP_SPEAKERS = {"rin", "all"}


def load_act_sentences(timeline_path: Path) -> tuple[float, list[dict]]:
    """Group split subtitle cards into whole spoken sentences."""
    data = json.loads(timeline_path.read_text(encoding="utf-8"))
    sentences: list[dict] = []
    for card in data["cards"]:
        who = card["who"]
        cid = card.get("id", "")
        ls = float(card["lineStart"])
        st = float(card["start"])
        ed = float(card["end"])
        if (
            sentences
            and sentences[-1]["who"] == who
            and sentences[-1]["id"] == cid
            and abs(sentences[-1]["lineStart"] - ls) < 1e-4
        ):
            sentences[-1]["text"] += card["text"]
            sentences[-1]["end"] = max(sentences[-1]["end"], ed)
            sentences[-1]["cards"] += 1
        else:
            sentences.append({
                "id": cid,
                "who": who,
                "text": card["text"],
                "start": st,
                "end": ed,
                "lineStart": ls,
                "cards": 1,
            })
    return float(data.get("duration", 0.0)), sentences


def render_aligned_preview(items: list[dict], total_dur: float, out_path: Path) -> tuple[int, list[str]]:
    """Render AI sentences into one timeline-aligned mono 16-bit WAV for quick checking."""
    warnings: list[str] = []
    sr_ref = None
    loaded = []
    max_end = total_dur
    cursor_s = 0.0

    for item in items:
        wav_path = Path(item["wav_abs"])
        if not wav_path.is_file():
            continue
        sr, samples = load_wav_mono(wav_path)
        if sr_ref is None:
            sr_ref = sr
        elif sr != sr_ref:
            warnings.append(f"{item['file']}: sample rate {sr} != {sr_ref}, skipped in preview mix")
            continue
        dur = len(samples) / sr
        placed_start = max(item["lineStart"], cursor_s)
        if placed_start > item["lineStart"] + 0.05:
            warnings.append(
                f"{item['file']}: shifted +{placed_start - item['lineStart']:.2f}s due to previous overrun"
            )
        slot_dur = item["slot_dur"]
        if dur > slot_dur + 0.05:
            warnings.append(
                f"{item['file']} ({item['who']}): tts={dur:.2f}s > slot={slot_dur:.2f}s (+{dur - slot_dur:.2f}s)"
            )
        item["tts_dur"] = round(dur, 3)
        item["overrun"] = round(dur - slot_dur, 3)
        cursor_s = placed_start + dur + 0.05
        max_end = max(max_end, placed_start + dur + 0.5)
        loaded.append((placed_start, normalize_rms(samples)))

    if sr_ref is None or not loaded:
        return 0, warnings

    total_frames = int(math_ceil(max_end * sr_ref))
    buf = array.array("h", [0]) * total_frames
    if sys.byteorder == "big":
        buf.byteswap()
    for start_s, samples in loaded:
        off = int(round(start_s * sr_ref))
        for idx, s in enumerate(samples):
            pos = off + idx
            if 0 <= pos < total_frames:
                cur = buf[pos] + int(s * 32767.0)
                buf[pos] = max(-32767, min(32767, cur))

    import wave
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(out_path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr_ref)
        w.writeframes(buf.tobytes())
    return sr_ref, warnings


def math_ceil(x: float) -> int:
    i = int(x)
    return i if x == i else i + 1


def main() -> None:
    ensure_utf8_stdio()
    ap = argparse.ArgumentParser(description="Generate Fish Audio TTS for Devlog 5-act timelines.")
    ap.add_argument("--model", default=os.environ.get("FISH_MODEL", "s2.1-pro-free"))
    ap.add_argument("--acts", nargs="*", default=ACTS, help=f"Subset of acts from: {', '.join(ACTS)}")
    ap.add_argument("--dry-run", action="store_true", help="Show line/byte counts and estimated cost")
    ap.add_argument("--force", action="store_true", help="Re-generate even if WAV already exists")
    ap.add_argument("--limit", type=int, default=0, help="Stop after N new API calls (0 = unlimited)")
    ap.add_argument("--delay", type=float, default=0.5, help="Pause in seconds between API calls")
    ap.add_argument("--out-dir", default=str(BASE_DIR / "output"), help="Output root directory")
    ap.add_argument("--no-mix", action="store_true", help="Skip aligned_ai_preview.wav rendering")
    args = ap.parse_args()

    ref_cache = load_ref_cache(BASE_DIR)
    selected_acts = [a for a in ACTS if a in set(args.acts)]
    if not selected_acts:
        raise SystemExit(f"No valid acts in --acts {args.acts}. Choose from: {ACTS}")

    act_plans = []
    total_ai = 0
    total_bytes = 0
    for act in selected_acts:
        tl_path = BASE_DIR / "timelines" / f"timeline_{act}.json"
        if not tl_path.is_file():
            raise FileNotFoundError(f"Missing timeline file: {tl_path}")
        act_dur, sentences = load_act_sentences(tl_path)
        ai_items = []
        seq = 0
        for idx, s in enumerate(sentences):
            if s["who"] in SKIP_SPEAKERS:
                continue
            if s["who"] not in ref_cache:
                raise KeyError(f"Unknown speaker {s['who']!r} in {tl_path.name}")
            seq += 1
            next_start = sentences[idx + 1]["lineStart"] if idx + 1 < len(sentences) else act_dur
            slot_dur = max(s["end"] - s["lineStart"], next_start - s["lineStart"])
            safe_id = re.sub(r"[^0-9A-Za-z_-]", "_", s["id"]) if s["id"] else f"L{seq:02d}"
            fn = f"{seq:03d}_{s['who']}_{safe_id}.wav"
            ai_items.append({
                "seq": seq,
                "id": s["id"],
                "who": s["who"],
                "lineStart": round(s["lineStart"], 3),
                "card_end": round(s["end"], 3),
                "slot_dur": round(slot_dur, 3),
                "file": fn,
                "text": s["text"],
            })
        b = sum(len(x["text"].encode("utf-8")) for x in ai_items)
        total_ai += len(ai_items)
        total_bytes += b
        act_plans.append((ACTS.index(act) + 1, act, act_dur, ai_items, b))

    if args.dry_run:
        est_pro = total_bytes / 1_000_000 * 15.0
        est_drama = total_bytes / 1_000_000 * 50.0
        print(f"Dry-run model={args.model}, acts={selected_acts}")
        for act_num, act, act_dur, ai_items, b in act_plans:
            print(f"  {act_num}_{act:4s} dur={act_dur:6.1f}s ai_lines={len(ai_items):2d} utf8_bytes={b:5d}")
        print(f"TOTAL: ai_lines={total_ai}, utf8_bytes={total_bytes}")
        print(f"Estimated paid cost: s2.1-pro ~${est_pro:.2f} | drama-3 ~${est_drama:.2f} | s2.1-pro-free $0")
        return

    api_key = os.environ.get("FISH_API_KEY", "").strip()
    if not api_key:
        raise SystemExit(
            "Missing FISH_API_KEY env var!\n"
            "  Windows PowerShell: $env:FISH_API_KEY='your_key'\n"
            "  macOS / Linux:      export FISH_API_KEY='your_key'"
        )

    out_root = Path(args.out_dir)
    calls_made = 0
    for act_num, act, act_dur, ai_items, _ in act_plans:
        act_dir = out_root / f"{act_num:02d}_{act}"
        act_dir.mkdir(parents=True, exist_ok=True)
        print(f"\n=== [{act_num:02d}_{act}] {len(ai_items)} AI lines (model={args.model}) ===", flush=True)

        for item in ai_items:
            wav_path = act_dir / item["file"]
            item["wav_abs"] = str(wav_path)
            if wav_path.is_file() and wav_path.stat().st_size > 44 and not args.force:
                sr, x = load_wav_mono(wav_path)
                item["tts_dur"] = round(len(x) / sr, 3)
                item["overrun"] = round(item["tts_dur"] - item["slot_dur"], 3)
                print(f"  SKIP {item['file']:24s} (exists, {item['tts_dur']:.2f}s)", flush=True)
                continue

            if args.limit and calls_made >= args.limit:
                print(f"  Reached --limit {args.limit}, stopping early (re-run later to resume).", flush=True)
                return

            t0 = time.time()
            audio = call_fish_tts(api_key, args.model, item["text"], ref_cache[item["who"]])
            wav_path.write_bytes(audio)
            calls_made += 1
            sr, x = load_wav_mono(wav_path)
            dur = len(x) / sr
            item["tts_dur"] = round(dur, 3)
            item["overrun"] = round(dur - item["slot_dur"], 3)
            flag = " [OVERRUN]" if item["overrun"] > 0.05 else ""
            print(
                f"  OK   {item['file']:24s} tts={dur:5.2f}s slot={item['slot_dur']:5.2f}s ({time.time()-t0:.1f}s){flag}",
                flush=True,
            )
            if args.delay > 0:
                time.sleep(args.delay)

        if not args.no_mix:
            sr_used, warnings = render_aligned_preview(ai_items, act_dur, act_dir / "aligned_ai_preview.wav")
            if sr_used:
                print(f"  Mixed aligned_ai_preview.wav ({sr_used}Hz)", flush=True)
            for w_msg in warnings:
                print(f"  WARN: {w_msg}", flush=True)

        manifest = [{k: v for k, v in item.items() if k != "wav_abs"} for item in ai_items]
        (act_dir / "manifest.json").write_text(
            json.dumps({"act": act, "duration": act_dur, "model": args.model, "lines": manifest}, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )


if __name__ == "__main__":
    main()
