"""Offline SoundFont renderer for v6 (stereo-balanced, warm chamber mix)."""
from pathlib import Path
import sys
import json
import math
import wave
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RT = ROOT / '.tools/devlog-audio'
sys.path.insert(0, str(RT / 'python'))

import numpy as np
from scipy import signal
import tinysoundfont
import imageio_ffmpeg

SR = 48000
SF = RT / 'soundfonts/GeneralUser-GS.sf2'
FF = imageio_ffmpeg.get_ffmpeg_exe()

DURATION = 0.0
PARTS = {}
NOTES = {}


def curve(name, times):
    return np.ones_like(times, dtype=np.float32)


def compressor(a, threshold=-25, ratio=1.6, knee=6, attack=0.018, release=0.15):
    """Stereo-linked RMS detector at 1 ms control rate."""
    hop = 48
    n = len(a)
    pad = (-n) % hop
    x = np.pad(a, ((0, pad), (0, 0))).reshape(-1, hop, 2)
    level = 10 * np.log10(np.maximum(np.mean(x * x, axis=(1, 2)), 1e-12))
    delta = level - threshold
    wanted = np.where(
        delta < -knee / 2,
        0.0,
        np.where(
            delta > knee / 2,
            delta * (1 - 1 / ratio),
            (delta + knee / 2) ** 2 / (2 * knee) * (1 - 1 / ratio),
        ),
    )
    aa = math.exp(-0.001 / attack)
    ar = math.exp(-0.001 / release)
    reduction = np.empty_like(wanted)
    state = 0.0
    for i, val in enumerate(wanted):
        coef = aa if val > state else ar
        state = coef * state + (1 - coef) * val
        reduction[i] = state
    gain = 10 ** (-np.interp(np.arange(n), np.arange(len(reduction)) * hop, reduction) / 20)
    return (a * gain[:, None]).astype(np.float32), float(reduction.max())


def render(name, shaped=True):
    """Render one instrument track via TinySoundFont and shape EQ/level/pan."""
    program, ch, pan, target, send = PARTS[name]
    synth = tinysoundfont.Synth(gain=-9, samplerate=SR)
    sid = synth.sfload(str(SF))
    synth.program_select(ch, sid, 128 if ch == 9 else 0, program, is_drums=(ch == 9))
    synth.control_change(ch, 10, 64)
    n = round(DURATION * SR)
    out = np.zeros((n, 2), np.float32)

    events = []
    for t, p, d, vel in NOTES[name]:
        events.append((round(t * SR), 1, p, vel))
        events.append((round((t + d) * SR), 0, p, 0))

    pos = 0
    for at, kind, p, vel in sorted(events) + [(n, 2, 0, 0)]:
        at = min(max(0, at), n)
        if at > pos:
            out[pos:at] = np.frombuffer(synth.generate(at - pos), np.float32).reshape(-1, 2)
            pos = at
        if kind == 1:
            synth.noteon(ch, p, vel)
        elif kind == 0:
            synth.noteoff(ch, p)

    cutoff_hp = 38 if name == 'shared_bass' else 85
    cutoff_lp = 5500 if name == 'soft_pad' else 7200
    out = signal.sosfilt(signal.butter(2, cutoff_hp, 'highpass', fs=SR, output='sos'), out, axis=0).astype(np.float32)
    out = signal.sosfilt(signal.butter(2, cutoff_lp, 'lowpass', fs=SR, output='sos'), out, axis=0).astype(np.float32)

    if len(NOTES[name]) > 0:
        pow_blocks = np.mean(out[: len(out) // 4800 * 4800].reshape(-1, 4800, 2) ** 2, axis=(1, 2))
        active = pow_blocks[pow_blocks > max(pow_blocks.max() * 0.012, 1e-12)]
        rms = float(np.sqrt(np.mean(active))) if len(active) else 1e-9
        out *= 10 ** (target / 20) / max(rms, 1e-9)

    reduction = 0.0
    if shaped and len(NOTES[name]) > 0:
        out *= curve(name, np.arange(n) / SR).astype(np.float32)[:, None]
        if name in ['astra_epiano', 'shared_guitar', 'shared_bass']:
            out, reduction = compressor(out, threshold=-25 if name == 'astra_epiano' else -31)

    out[:, 0] *= math.sqrt(max(0.0, 1 - pan))
    out[:, 1] *= math.sqrt(max(0.0, 1 + pan))
    return out, send, reduction


def make_reverb_ir(length_s=0.85, decay=8.5, seed=120):
    rng = np.random.default_rng(seed)
    t = np.arange(int(length_s * SR)) / SR
    ir = rng.normal(size=len(t)) * np.exp(-t * decay)
    ir[: int(0.024 * SR)] = 0
    ir = signal.sosfilt(signal.butter(2, 4000, 'lowpass', fs=SR, output='sos'), ir)
    return (ir / np.linalg.norm(ir) * 0.42).astype(np.float32)


def writewav(path, a):
    assert np.isfinite(a).all() and np.max(np.abs(a)) < 1.0
    with wave.open(str(path), 'wb') as f:
        f.setnchannels(2)
        f.setsampwidth(2)
        f.setframerate(SR)
        f.writeframes((a * 32767).astype('<i2').tobytes())


def loudness(path):
    r = subprocess.run(
        [FF, '-hide_banner', '-i', str(path), '-af', 'loudnorm=I=-20:TP=-1.5:LRA=12:print_format=json', '-f', 'null', '-'],
        capture_output=True,
        check=True,
    )
    s = r.stderr.decode('utf8', 'replace')
    j = json.loads(s[s.rfind('{'): s.rfind('}') + 1])
    return {k: float(j[k]) for k in ['input_i', 'input_tp', 'input_lra']}
