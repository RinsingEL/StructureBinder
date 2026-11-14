"""Original v6 score composer & renderer for Act 1 (Opening) + Act 2 (W -> T -> D).

Themes:
  - Theme A  (Act 1, 0..228 beats, D major): Warm clubroom afternoon tea party.
  - Theme B1 (Act 2 pre-Mirror Tide, 228..700 beats, D major / Lydian color): World scan, seas, 7 nations.
  - Theme B2 (Act 2 Mirror Tide Academy -> Observatory, 700..1812 beats, G major): City craft, D4 twist, Observatory duet.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import math
import subprocess
from pathlib import Path

import mido
import numpy as np
from scipy import interpolate, signal

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]

spec_r = importlib.util.spec_from_file_location('renderer_v6', HERE / 'renderer.py')
v = importlib.util.module_from_spec(spec_r)
spec_r.loader.exec_module(v)

spec_m = importlib.util.spec_from_file_location('motifs_v6', HERE / 'motifs.py')
motifs = importlib.util.module_from_spec(spec_m)
spec_m.loader.exec_module(motifs)

SR = v.SR
FF = v.FF
BPM = 108
BEAT_S = 60.0 / BPM

WHO = {
    'rin': 'rin_piano',
    'gemini': 'gemini_flute',
    'claude': 'claude_clarinet',
    'astra': 'astra_epiano',
    'deepseek': 'deepseek_pizz',
    'glm': 'glm_vibes',
}

# Wider, natural clubroom table stereo placement
PARTS = {
    'rin_piano':        (0,  0, -0.18, -26.4, 0.16),
    'gemini_flute':     (73, 1, -0.38, -28.0, 0.18),
    'claude_clarinet':  (71, 2, -0.24, -26.0, 0.16),
    'astra_epiano':     (4,  3,  0.22, -25.8, 0.14),
    'deepseek_pizz':    (45, 4,  0.40, -26.5, 0.12),
    'glm_vibes':        (11, 5,  0.30, -27.2, 0.15),
    'shared_guitar':    (24, 6, -0.30, -30.5, 0.11),
    'shared_bass':      (32, 7,  0.00, -28.8, 0.03),
    'soft_pad':         (89, 8,  0.00, -38.2, 0.12),
    'quiet_percussion': (40, 9,  0.08, -34.5, 0.04),
}

# Warm, audible-register chord voicings: (bass1, alt_bass, upper_guitar_notes)
CHORDS = {
    # D major palette (Theme A & Theme B1)
    'D':      (38, 45, [57, 61, 64, 66]),   # Dmaj9 (D2 -> A2)
    'A/Cs':   (37, 45, [57, 61, 64, 69]),   # A/C#  (C#2 -> A2)
    'Bm7':    (35, 42, [57, 59, 62, 66]),   # Bm7   (B1 -> F#2)
    'Fsm7':   (42, 45, [57, 61, 64, 66]),   # F#m7  (F#2 -> A2, root F#4 replaces G#4)
    'Gmaj7':  (43, 38, [55, 59, 62, 66]),   # Gmaj7 (G2 -> D2)
    'D/Fs':   (42, 38, [57, 62, 66, 69]),   # D/F#  (F#2 -> D2)
    'Em7':    (40, 47, [55, 59, 62, 66]),   # Em9   (E2 -> B2)
    'A7sus':  (45, 40, [55, 57, 62, 64]),   # A7sus4(A2 -> E2)
    'A':      (45, 40, [57, 61, 64, 69]),   # A     (A2 -> E2)
    'Gm6':    (43, 38, [58, 62, 64, 67]),   # Gm6   (G2 -> D2)
    # G major palette (Theme B2 - Mirror Tide City)
    'G':      (43, 38, [55, 59, 62, 69]),   # Gadd9 (G2 -> D2, warm open G-B-D-A without F#/G crunch)
    'D/Fs_g': (42, 38, [57, 60, 62, 66]),   # D7/F# (F#2 -> D2, never C#2!)
    'Cmaj7':  (36, 43, [55, 60, 62, 64]),   # Cmaj9 (C2 -> G2, includes C4)
    'G/B':    (35, 38, [55, 59, 62, 67]),   # G/B   (B1 -> D2, never F#2)
    'Am7':    (45, 40, [55, 60, 64, 67]),   # Am7   (A2 -> E2)
    'D7sus':  (38, 45, [55, 57, 60, 64]),   # D9sus4(D2 -> A2)
    'D_g':    (38, 45, [57, 60, 62, 66]),   # D7    (D2 -> A2)
    'Cm6':    (36, 43, [55, 58, 60, 63]),   # D4 twist (C2 -> G2)
    'Am7b5':  (45, 45, [55, 58, 60, 63]),   # gentle tension before recovery (A2 pedal)
}


def load_timelines():
    op = json.loads((HERE / 'timeline_开场.json').read_text(encoding='utf8'))
    a2 = json.loads((HERE / 'timeline_第二段.json').read_text(encoding='utf8'))
    op_beats = round(op['duration'] / BEAT_S)
    a2_beats = round(a2['duration'] / BEAT_S)
    total_beats = op_beats + a2_beats
    duration_s = total_beats * BEAT_S

    events = []
    seen = set()
    for c in op['cards']:
        if c['who'] not in seen:
            seen.add(c['who'])
            events.append({
                'id': 'intro_' + c['who'],
                'who': c['who'],
                'beat': round(c['start'] / BEAT_S, 4),
                'hit': c['start'],
                'kind': 'intro',
            })

    # Map Act 2 PPT beats and speaker turns onto continuous score beats
    beat_lookup = {}
    BEATS_LIST = ['W1', 'W2', 'W3a', 'W3b', 'W3c', 'T1', 'T2', 'T3', 'T3-end', 'T4',
                  'D1', 'D1b', 'D1c', 'D1d', 'D1e', 'D2', 'D3', 'D4', 'D5a', 'D5b', 'D6', 'D7', 'D-photos']
    for b in a2['beats']:
        beat_lookup[BEATS_LIST[b['index']]] = op_beats + round(b['t'] / BEAT_S, 4)

    prev_who = None
    obs_beat = None
    for c in a2['cards']:
        is_line_start = abs(c['start'] - c['lineStart']) < 1e-6
        b_abs = op_beats + round(c['start'] / BEAT_S, 4)
        ppt = c.get('beat')
        if c['id'] == '2-60' and is_line_start:
            obs_beat = b_abs
        if ppt or (is_line_start and c['who'] != prev_who) or c['id'] == '2-60':
            events.append({
                'id': c['id'],
                'who': c['who'],
                'beat': b_abs,
                'hit': b_abs * BEAT_S,
                'kind': ppt or 'speaker',
            })
        if is_line_start:
            prev_who = c['who']

    sections = [
        ('title', 0.0),
        ('welcome', events[0]['beat']),
        ('intros_warm', next(e['beat'] for e in events if e['id'] == 'intro_claude')),
        ('intros_playful', next(e['beat'] for e in events if e['id'] == 'intro_deepseek')),
        ('handoff', op_beats + round(a2['cards'][0]['start'] / BEAT_S, 4)),
        ('blank', beat_lookup['W1']),
        ('scan', beat_lookup['W2']),
        ('terrain', beat_lookup['W3a']),
        ('nations', beat_lookup['T1']),
        ('sites', beat_lookup['T2']),
        ('expand', beat_lookup['T3']),
        ('t3_end', beat_lookup['T3-end']),
        ('city_enter', beat_lookup['T4']),
        ('city_zoom', beat_lookup['D1']),
        ('districts', beat_lookup['D1e']),
        ('dorm', beat_lookup['D2']),
        ('d4_twist', beat_lookup['D4']),
        ('d5_fix', beat_lookup['D5a']),
        ('d6_solve', beat_lookup['D6']),
        ('d7_fast', beat_lookup['D7']),
        ('d_photos', beat_lookup['D-photos']),
        ('observatory', obs_beat),
    ]
    return op, a2, op_beats, total_beats, duration_s, events, sections


def make_score(op_beats, total_beats, duration_s, events, sections, latencies):
    NOTES = {n: [] for n in PARTS}
    ENV = {n: [] for n in PARTS}
    SEC = dict(sections)

    def sec_at(b):
        return next(k for k, start in reversed(sections) if b >= start - 1e-6)

    def theme_at(b):
        if b < SEC['handoff']:
            return 'A'
        if b < SEC['city_enter']:
            return 'B1'
        return 'B2'

    def key_tonic(b):
        return 62 if theme_at(b) in ('A', 'B1') else 67  # D4 vs G4

    def harmony(b):
        k = sec_at(b)
        th = theme_at(b)
        if th == 'A':
            prog = ['D', 'A/Cs', 'Bm7', 'Fsm7', 'Gmaj7', 'D/Fs', 'Em7', 'A7sus',
                    'D', 'A/Cs', 'Bm7', 'Fsm7', 'Gmaj7', 'Gm6', 'Em7', 'A']
            return prog[int(b // 4) % 16]
        if k == 'handoff':
            prog = ['D', 'Bm7', 'Gmaj7', 'A7sus']
            return prog[int((b - SEC['handoff']) // 4) % 4]
        if k == 'blank':
            return ['D', 'Gmaj7', 'D', 'A7sus'][int((b - SEC['blank']) // 4) % 4]
        if k == 'scan':
            prog = ['D', 'A/Cs', 'Bm7', 'Gmaj7', 'D/Fs', 'Em7', 'Gmaj7', 'A7sus']
            return prog[int((b - SEC['scan']) // 4) % 8]
        if k == 'terrain':
            prog = ['Gmaj7', 'D/Fs', 'Em7', 'Bm7', 'Gmaj7', 'D/Fs', 'Em7', 'A7sus']
            return prog[int((b - SEC['terrain']) // 4) % 8]
        if k == 't3_end':
            # Warm 6-bar pivot bridge: F#m7 -> Bm7 -> Em7 -> A7sus -> D7sus -> D7 (resolves smoothly into G at 6:29)
            prog = ['Fsm7', 'Bm7', 'Em7', 'A7sus', 'D7sus', 'D_g']
            return prog[min(5, int((b - SEC['t3_end']) // 4))]
        if k in ('nations', 'sites', 'expand'):
            prog = ['Gmaj7', 'A', 'Fsm7', 'Bm7', 'Em7', 'A', 'D', 'D/Fs',
                    'Gmaj7', 'A', 'Fsm7', 'Bm7', 'Em7', 'Gmaj7', 'A7sus', 'A7sus']
            return prog[int((b - SEC['nations']) // 4) % 16]
        # Theme B2 (G major city)
        if k == 'd4_twist':
            rel_bar = int((b - SEC['d4_twist']) // 4)
            if rel_bar == 0:
                return 'Cm6'
            if rel_bar == 1:
                return 'Am7b5'
            prog = ['G', 'D/Fs_g', 'Em7', 'G/B', 'Cmaj7', 'Am7', 'D7sus', 'D_g']
            return prog[(rel_bar - 2) % 8]
        if k == 'observatory':
            prog = ['Cmaj7', 'G/B', 'Am7', 'D7sus', 'G', 'D/Fs_g', 'Em7', 'D7sus']
            return prog[int((b - SEC['observatory']) // 4) % 8]
        prog = ['G', 'D/Fs_g', 'Em7', 'G/B', 'Cmaj7', 'G/B', 'Am7', 'D7sus',
                'G', 'D/Fs_g', 'Em7', 'Bm7', 'Cmaj7', 'Am7', 'D7sus', 'G']
        return prog[int((b - SEC['city_enter']) // 4) % 16]

    def add(part, b, p, dur_beats, vel, latency=0.0):
        t = max(0.0, b * BEAT_S - latency)
        end = (b + dur_beats) * BEAT_S - latency
        if t < duration_s - 0.5:
            NOTES[part].append((t, int(p), max(0.07, end - t), int(np.clip(vel, 20, 118))))

    def add_env(part, b_start, b_end, strength=0.90, latency=0.0):
        t = max(0.0, b_start * BEAT_S - latency)
        end = b_end * BEAT_S - latency
        ENV[part].append({
            'times': [max(0.0, t - 0.14), t, t + 0.25, (t + end) * 0.5, max(t + 0.26, end - 0.10), end + 0.45],
            'values': [0.12, strength * 0.85, strength, strength * 0.85, strength * 0.48, 0.12],
        })

    def render_motif(part, start_b, max_len_beats, motif_items, scale_vel=1.0, latency=0.0, support=False):
        used = []
        base_tonic = key_tonic(start_b) + motifs.BASE_TONIC_OFFSET[part]
        for off, degs, d, vel in motif_items:
            if off >= max_len_beats - 0.5:
                continue
            b = start_b + off
            chord_sym = harmony(b)
            _, _, chord_tones = CHORDS[chord_sym]
            tonic_pc = key_tonic(b) % 12
            deg_list = degs if isinstance(degs, list) else [degs]
            dur_clamped = min(d, max_len_beats - off - 0.1)
            if dur_clamped <= 0.1:
                continue
            for deg in deg_list:
                raw_p = motifs.degree_to_midi(base_tonic, deg)
                # Keep Vibes in a warm mid-bell range when supporting
                if part == 'glm_vibes' and support:
                    raw_p += 12
                p = motifs.snap_to_harmony(raw_p, chord_tones, tonic_pc, chord_sym)
                add(part, b, p, dur_clamped, round(vel * scale_vel), latency)
            used.append((off, dur_clamped))
        if used:
            end_b = start_b + max(off + d for off, d in used)
            add_env(part, start_b, end_b, 0.40 if support else 0.92, latency)

    # 1. Opening Title Gesture (0..6 beats): clean, steady piano solo only (no extra wind voice)
    for b_off, p, d, vel in [(1.0, 62, 1.4, 48), (2.0, 66, 1.4, 52), (3.0, 69, 2.0, 54), (4.0, 74, 2.2, 50)]:
        add('rin_piano', b_off, p, d, vel)
    add_env('rin_piano', 1.0, 6.0, 0.72)

    # 2. Accompaniment Grid (aligned strictly to 4-beat bars)
    total_bars = math.ceil(total_beats / 4)
    for bar in range(total_bars):
        base = bar * 4
        if base >= total_beats - 1:
            break
        k = sec_at(base)
        th = theme_at(base)
        sym = harmony(base)
        root, alt_bass, ch = CHORDS[sym]

        # Dramatic D4 pause on bar 0 of d4_twist ("然后，程序算完了……只落下了一栋。")
        if k == 'd4_twist' and base < SEC['d4_twist'] + 4:
            # One quiet quizzical pizzicato drop + soft suspended piano chord
            add('deepseek_pizz', base + 0.0, ch[3], 0.4, 58, latencies['deepseek_pizz'])
            add('deepseek_pizz', base + 1.5, ch[1], 0.4, 50, latencies['deepseek_pizz'])
            add_env('deepseek_pizz', base, base + 2.5, 0.72, latencies['deepseek_pizz'])
            add('shared_bass', base + 0.0, root, 2.5, 42)
            continue

        # Blank canvas: ultra-sparse breathing room
        if k == 'blank':
            if bar % 2 == 0:
                for p in ch[1:]:
                    add('soft_pad', base, p, 7.6, 36)
                add('rin_piano', base + 0.0, ch[3] + 12, 2.5, 42)
                add_env('rin_piano', base, base + 3.0, 0.48)
            continue

        # Guitar patterns by section meaning
        if k == 'title':
            pat = {} if bar == 0 else {2.0: (0, 2.2, 42)}
        elif k in ('welcome', 'intros_warm'):
            pat = {0.0: (0, 1.1, 46), 1.5: (2, 0.75, 40), 2.5: (1, 0.85, 43), 3.5: (3, 0.4, 38)}
        elif k == 'intros_playful':
            pat = {0.0: (0, 0.85, 47), 1.0: (2, 0.65, 41), 2.5: (1, 0.85, 44), 3.5: (3, 0.35, 38)}
        elif k == 'handoff':
            pat = {0.0: (0, 2.2, 40), 2.0: (2, 1.5, 36)}
        elif k == 'scan':
            # Crisp grid scan
            pat = {0.0: (0, 0.7, 45), 1.0: (2, 0.7, 39), 2.0: (1, 0.7, 42), 3.0: (3, 0.7, 37)}
            add('deepseek_pizz', base + 0.5, ch[2], 0.25, 36, latencies['deepseek_pizz'])
            add('deepseek_pizz', base + 2.5, ch[1], 0.25, 34, latencies['deepseek_pizz'])
            add_env('deepseek_pizz', base, base + 3.5, 0.36, latencies['deepseek_pizz'])
        elif k == 'terrain':
            pat = {0.0: (0, 2.6, 41), 2.0: (2, 1.6, 36)} if bar % 2 == 0 else {0.0: (3, 2.8, 40)}
        elif k in ('nations', 'sites', 'expand'):
            pat = {0.0: (0, 1.1, 46), 1.5: (2, 0.7, 40), 2.0: (1, 1.1, 43), 3.5: (3, 0.4, 38)}
        elif k == 't3_end':
            pat = {0.0: (0, 2.4, 41), 2.0: (2, 1.5, 36)}
        elif k == 'districts':
            # 16 districts card-flip sparkle
            pat = {0.0: (0, 0.8, 45), 1.0: (2, 0.7, 40), 2.0: (1, 0.8, 43), 3.0: (3, 0.7, 38)}
            for step in (0, 1, 2, 3):
                add('glm_vibes', base + step, ch[step % 4] + 12, 0.8, 40 + step * 3)
            add_env('glm_vibes', base, base + 3.8, 0.45)
        elif k == 'd7_fast':
            # Energetic 16-district fast-forward ramp
            pat = {0.0: (0, 0.8, 48), 0.5: (1, 0.45, 38), 1.0: (2, 0.7, 43), 2.0: (1, 0.8, 46), 3.0: (3, 0.7, 41)}
        elif k == 'observatory':
            # Lush warm arpeggio supporting the Observatory duet
            pat = {0.0: (0, 1.3, 44), 1.0: (1, 1.1, 39), 2.0: (2, 1.2, 42), 3.0: (3, 0.9, 38)}
        else:
            # Theme B2 default (handcrafted G-major city)
            if bar % 2 == 0:
                pat = {0.0: (0, 1.1, 44), 1.5: (2, 0.7, 38), 2.5: (1, 0.9, 41), 3.5: (3, 0.4, 36)}
            else:
                pat = {0.0: (0, 1.4, 42), 2.0: (2, 1.4, 38)}

        for bt, (idx, d, vel) in pat.items():
            add('shared_guitar', base + bt, ch[idx], d, vel)

        # Bass line
        if k == 't3_end':
            add('shared_bass', base, root, 3.4, 44)
        elif k not in ('title', 'handoff'):
            add('shared_bass', base, root, 2.6 if k in ('terrain', 'observatory') else 1.7, 54)
            if k not in ('terrain',):
                add('shared_bass', base + 2.0, alt_bass, 1.5, 44)

        # Quiet brush percussion
        if k in ('welcome', 'intros_warm', 'intros_playful'):
            add('quiet_percussion', base + 1.0, 37, 0.12, 30)
            add('quiet_percussion', base + 3.0, 37, 0.12, 30)
            if k == 'intros_playful':
                add('quiet_percussion', base + 2.5, 42, 0.10, 24)
        elif k == 'scan':
            for bt in (0.0, 1.0, 2.0, 3.0):
                add('quiet_percussion', base + bt, 42, 0.10, 28 if bt in (0, 2) else 21)
        elif k in ('nations', 'expand', 'd7_fast'):
            for bt in (0.5, 1.5, 2.5, 3.5):
                add('quiet_percussion', base + bt, 42, 0.09, 25 if k == 'd7_fast' else 21)
            if k == 'd7_fast':
                add('quiet_percussion', base + 1.0, 37, 0.12, 28)
                add('quiet_percussion', base + 3.0, 37, 0.12, 28)
        elif th == 'B2' and k not in ('d4_twist', 'observatory', 'd_photos') and bar % 2 == 0:
            add('quiet_percussion', base + 1.0, 42, 0.10, 20)
            add('quiet_percussion', base + 3.0, 42, 0.10, 20)

        # Warm Pad swells on atmospheric sections (including subtle t3_end -> B2 bridge)
        if k in ('intros_warm', 'terrain', 't3_end', 'd_photos', 'observatory') and (bar % 2 == 0 or k in ('observatory', 't3_end')):
            for p in ch[1:]:
                add('soft_pad', base, p, 3.8, 40 if k == 'observatory' else 34)

    # 3. Topic Landmarks (soft guitar chord accents on major Act 2 landscape transitions; skip T4/D1 so B2 glides in gently)
    for e in events:
        if e['kind'] in ('W1', 'W2', 'W3a', 'T1', 'D7', 'D-photos'):
            _, _, ch = CHORDS[harmony(e['beat'])]
            for p in ch[:3]:
                add('shared_guitar', e['beat'], p, 1.2, 46)

    # 4. Character Motifs & Dialogue Ensemble
    obs_start = SEC['observatory']
    campus_anchors = {'T4', 'D1', 'D2', 'D5a', 'D6', 'D-photos'}
    hard_stop_kinds = campus_anchors | {'D4'}
    last_solo_beat = {}
    relay = None  # (anchor_beat, anchor_end) for spanning 8-bar anchor themes across 2 speakers
    for i, e in enumerate(events):
        who = e['who']
        part = WHO[who]
        b = e['beat']
        next_b = events[i + 1]['beat'] if i + 1 < len(events) else total_beats
        avail = next_b - b

        # Observatory Duet handles 2-60..2-63 specially
        if obs_start is not None and obs_start <= b < obs_start + 36:
            if e['id'] == '2-60':
                render_motif('rin_piano', b, 34.0, motifs.OBSERVATORY_PIANO, 1.0, latencies['rin_piano'])
                render_motif('gemini_flute', b, 34.0, motifs.OBSERVATORY_FLUTE, 0.96, latencies['gemini_flute'])
            continue

        # Skip normal lead phrase right on D4's dramatic 1-bar pause
        if e['kind'] == 'D4':
            relay = None
            continue

        in_b2 = b >= SEC['city_enter']
        if in_b2:
            # Theme B2: Anchor sections state a full 8-bar (32b) Campus Theme when clear of the next anchor,
            # allowing Speaker 1 on Bars 1-4 and Speaker 2 (e.g. Astra after D6) to relay through Bars 5-8.
            if e['kind'] in campus_anchors:
                next_hard = next((ev['beat'] for ev in events[i + 1:] if ev['kind'] in hard_stop_kinds or ev['beat'] >= obs_start), total_beats)
                theme_len = 32.0 if (next_hard - b >= 32.0) else 16.0
                piano_scale = 0.80 if part != 'rin_piano' else 0.90
                render_motif('rin_piano', b, theme_len, motifs.CAMPUS_THEME_B2, piano_scale, latencies['rin_piano'])
                last_solo_beat['rin_piano'] = b
                relay = (b, b + theme_len) if (theme_len >= 32.0 and avail < 26.0) else None
                if part != 'rin_piano':
                    duet_line = motifs.ANCHOR_DUETS['B2'].get(part)
                    if duet_line:
                        render_motif(part, b, 16.0, duet_line, 0.86, latencies[part])
                        last_solo_beat[part] = b
                    if theme_len >= 32.0 and avail >= 26.0:
                        p2_same = motifs.ANCHOR_DUETS_P2['B2'].get(part)
                        if p2_same:
                            render_motif(part, b, 32.0, p2_same, 0.84, latencies[part])
                            last_solo_beat[part] = b + 16.0
                    if avail >= 36.0:
                        b2_vars = motifs.B2_CONVERSATIONS[part]
                        render_motif(part, b + 32.0, min(6.5, avail - 33.5), b2_vars[i % len(b2_vars)], 0.80, latencies[part])
                        last_solo_beat[part] = b + 32.0
                continue

            # If a 2nd speaker enters during Bars 5-8 of an active 8-bar Theme Anchor, relay together cleanly!
            if relay is not None and b < relay[1] - 4.0:
                a_b, a_end = relay
                relay = None
                if part != 'rin_piano' and b >= a_b + 11.0:
                    p2 = motifs.ANCHOR_DUETS_P2['B2'].get(part)
                    if p2:
                        render_motif(part, a_b, 32.0, p2, 0.86, latencies[part])
                        last_solo_beat[part] = a_b + 16.0
                    tail_start = max(a_end + 1.0, b + 20.0)
                    if b + avail >= tail_start + 6.0:
                        b2_vars = motifs.B2_CONVERSATIONS[part]
                        render_motif(part, tail_start, min(6.5, b + avail - tail_start - 1.5), b2_vars[(i + 1) % len(b2_vars)], 0.78, latencies[part])
                        last_solo_beat[part] = tail_start
                    continue
            relay = None

            # Regular dialogue in B2: relaxed mid-register conversational phrasing
            if b - last_solo_beat.get(part, -999.0) >= 8.0:
                b2_vars = motifs.B2_CONVERSATIONS[part]
                phrase_len = min(6.5, max(2.5, avail - 1.0))
                render_motif(part, b, phrase_len, b2_vars[i % len(b2_vars)], 0.84, latencies[part])
                last_solo_beat[part] = b
                if avail >= 22:
                    render_motif(part, b + 10.0, min(6.0, avail - 12.0), b2_vars[(i + 1) % len(b2_vars)], 0.78, latencies[part])
                if avail >= 36:
                    render_motif(part, b + 24.0, min(6.0, avail - 26.0), b2_vars[(i + 1) % len(b2_vars)], 0.74, latencies[part])

            if i > 0 and avail >= 16 and events[i - 1]['who'] != who:
                prev_part = WHO[events[i - 1]['who']]
                render_motif(prev_part, b + 6.0, 4.5, motifs.B2_SUPPORT_ECHOES[prev_part], 0.72, latencies[prev_part], support=True)
            continue

        # Theme A & Theme B1 (0:00..6:29): keep the beloved clubroom & world motifs intact
        lead_scale = 1.0 if b < SEC['handoff'] else 0.92
        lead_len = min(8.0, max(2.5, avail - 1.0))
        vars_list = motifs.CONVERSATION_VARIATIONS[part]
        # Avoid back-to-back duplicate PRIMARY_MOTIFS if the same speaker just started <12 beats earlier (e.g. T3-end)
        if b - last_solo_beat.get(part, -999.0) < 12.0 and e['kind'] == 'T3-end':
            render_motif(part, b, lead_len, vars_list[0], 0.86, latencies[part])
        else:
            render_motif(part, b, lead_len, motifs.PRIMARY_MOTIFS[part], lead_scale, latencies[part])
        last_solo_beat[part] = b

        # Conversational continuation inside long speeches (varied phrases, never identical loops)
        if avail >= 20 and not (part == 'rin_piano' and b < SEC['handoff']):
            render_motif(part, b + 10.0, min(6.0, avail - 12.0), vars_list[i % len(vars_list)], 0.84, latencies[part])
        if avail >= 34:
            render_motif(part, b + 24.0, min(6.0, avail - 26.0), vars_list[(i + 1) % len(vars_list)], 0.78, latencies[part])

        # Previous speaker softly nods/answers in the background
        if i > 0 and avail >= 16 and events[i - 1]['who'] != who:
            prev_part = WHO[events[i - 1]['who']]
            render_motif(prev_part, b + 6.0, 4.5, motifs.SUPPORT_ECHOES[prev_part], 0.75, latencies[prev_part], support=True)

    # Deduplicate overlapping identical pitch+onset
    for name in NOTES:
        merged = {}
        for t, p, d, vel in NOTES[name]:
            key = (round(t, 6), p)
            if key in merged:
                old = merged[key]
                merged[key] = (old[0], p, max(d, old[2]), max(vel, old[3]))
            else:
                merged[key] = (t, p, d, vel)
        NOTES[name] = sorted(merged.values())

    return NOTES, ENV


def profile_latency():
    result = {}
    for name, (program, ch, *_) in PARTS.items():
        if name not in WHO.values():
            continue
        s = v.tinysoundfont.Synth(gain=-9, samplerate=SR)
        sid = s.sfload(str(v.SF))
        s.program_select(0, sid, 0, program)
        s.noteon(0, 66, 64)
        a = np.frombuffer(s.generate(SR // 3), np.float32).reshape(-1, 2)
        a = signal.sosfilt(signal.butter(2, 85, 'highpass', fs=SR, output='sos'), a, axis=0)
        a = signal.sosfilt(signal.butter(2, 6500, 'lowpass', fs=SR, output='sos'), a, axis=0)
        env = np.sqrt(np.mean(a[: len(a) // 240 * 240].reshape(-1, 240, 2) ** 2, axis=(1, 2)))
        result[name] = int(np.flatnonzero(env >= env[:50].max() * 0.25)[0]) * 0.005
    return result


def build_expression(ENV, events, duration_s, op_beats):
    handoff_s = op_beats * BEAT_S

    def expression(name, times):
        a = np.asarray(times)
        out = np.full(a.shape, 0.12 if name in WHO.values() else 0.84, dtype=np.float32)
        if name in WHO.values():
            for env in ENV[name]:
                ts = np.array(env['times'], dtype=np.float64)
                vs = np.array(env['values'], dtype=np.float64)
                ix = np.argsort(ts)
                ts, vs = ts[ix], vs[ix]
                ts, idx = np.unique(ts, return_index=True)
                vs = vs[idx]
                if len(ts) < 2:
                    continue
                lo = np.searchsorted(a, ts[0])
                hi = np.searchsorted(a, ts[-1], side='right')
                if hi > lo:
                    out[lo:hi] = np.maximum(out[lo:hi], interpolate.PchipInterpolator(ts, vs)(a[lo:hi]))
        else:
            # Gentle ducking on speaker entrances so dialogue breathes
            for e in events:
                t = e['hit']
                lo = np.searchsorted(a, t - 0.18)
                hi = np.searchsorted(a, t + 1.2)
                if hi > lo:
                    out[lo:hi] *= np.interp(a[lo:hi], [t - 0.18, t + 0.08, t + 0.55, t + 1.2], [1.0, 0.82, 0.82, 1.0])
        # Shift gently from lively clubroom welcome to calmer explanatory backing
        out *= np.interp(a, [0, handoff_s, handoff_s + 10.0, duration_s], [1.0, 1.0, 0.84, 0.84])
        return out

    return expression


def export_midi(NOTES, expression, events, total_beats, duration_s):
    mid = mido.MidiFile(ticks_per_beat=960)
    meta = mido.MidiTrack()
    mid.tracks.append(meta)
    meta.extend([
        mido.MetaMessage('track_name', name='Geomantia v6 Conductor (108 BPM)'),
        mido.MetaMessage('key_signature', key='D'),
        mido.MetaMessage('time_signature', numerator=4, denominator=4),
        mido.MetaMessage('set_tempo', tempo=round(60.0 / BPM * 1e6), time=0),
    ])
    ev_meta = [(round(e['beat'] * 960), mido.MetaMessage('marker', text=f"{e['id']} {e['who']} {e['kind']}")) for e in events]
    pos = 0
    for tick, msg in sorted(ev_meta, key=lambda x: x[0]):
        msg.time = tick - pos
        meta.append(msg)
        pos = tick

    for name, (program, ch, pan, *_) in PARTS.items():
        tr = mido.MidiTrack()
        mid.tracks.append(tr)
        tr.append(mido.MetaMessage('track_name', name=name))
        tr.extend([
            mido.Message('program_change', channel=ch, program=program),
            mido.Message('control_change', channel=ch, control=10, value=round(64 + pan * 45)),
        ])
        ev = []
        for t, p, d, vel in NOTES[name]:
            ev.append((round(t / BEAT_S * 960), 0, mido.Message('note_on', channel=ch, note=p, velocity=vel)))
            ev.append((round((t + d) / BEAT_S * 960), 1, mido.Message('note_off', channel=ch, note=p, velocity=0)))
        ts = np.arange(0, duration_s, 0.08)
        ex = expression(name, ts)
        for t, amp in zip(ts, ex):
            ev.append((round(t / BEAT_S * 960), 2, mido.Message('control_change', channel=ch, control=11, value=round(float(np.clip(amp, 0, 1)) * 127))))
        pos = 0
        for tick, _, msg in sorted(ev, key=lambda x: (x[0], x[1])):
            msg.time = tick - pos
            tr.append(msg)
            pos = tick
    mid.save(HERE / '开场至第二段_v6.mid')


def write_highlight_sampler(mix_norm, op_beats, sections):
    """Assemble a ~2m30s highlight sampler across Theme A, B1, B2, D4 twist & Observatory duet."""
    SEC = dict(sections)
    slices = [
        ('开场 主题A', 0.0, 38.0),
        ('W1白纸->W2扫描 主题B1', max(0.0, SEC['blank'] * BEAT_S - 2.0), SEC['terrain'] * BEAT_S + 14.0),
        ('T4进镜潮学院 主题B2', SEC['city_enter'] * BEAT_S - 2.0, SEC['city_enter'] * BEAT_S + 28.0),
        ('D4只落下一栋', SEC['d4_twist'] * BEAT_S - 2.0, SEC['d4_twist'] * BEAT_S + 20.0),
        ('天象台二重奏', SEC['observatory'] * BEAT_S - 2.0, SEC['observatory'] * BEAT_S + 26.0),
    ]
    fade = int(0.45 * SR)
    chunks = []
    for idx, (_, t0, t1) in enumerate(slices):
        seg = mix_norm[int(t0 * SR): min(len(mix_norm), int(t1 * SR))].copy()
        if idx > 0:
            seg[:fade] *= np.linspace(0, 1, fade, dtype=np.float32)[:, None]
        if idx < len(slices) - 1:
            seg[-fade:] *= np.linspace(1, 0, fade, dtype=np.float32)[:, None]
        chunks.append(seg)
    sampler = np.concatenate(chunks, axis=0)
    sampler = np.clip(sampler, -0.99, 0.99)
    tmp = v.RT / 'v6-sampler.wav'
    v.writewav(tmp, sampler)
    out = HERE / '00_精华试听串烧_v6.wav'
    subprocess.run([FF, '-y', '-v', 'error', '-i', str(tmp), '-c:a', 'pcm_s24le', str(out)], check=True)
    subprocess.run([FF, '-y', '-v', 'error', '-i', str(out), '-c:a', 'libmp3lame', '-b:a', '224k', str(out.with_suffix('.mp3'))], check=True)


def main():
    parser = argparse.ArgumentParser(description='Compose & render v6 soundtrack (Act 1 + Act 2).')
    parser.add_argument('--soundfont', type=Path)
    args = parser.parse_args()
    if args.soundfont is not None:
        v.SF = args.soundfont.expanduser().resolve()
    if not v.SF.is_file():
        parser.error('SoundFont missing: ' + str(v.SF))
    v.RT.mkdir(parents=True, exist_ok=True)

    _, _, op_beats, total_beats, duration_s, events, sections = load_timelines()
    latencies = profile_latency()
    print('Latency compensation:', latencies, flush=True)

    NOTES, ENV = make_score(op_beats, total_beats, duration_s, events, sections, latencies)
    expression = build_expression(ENV, events, duration_s, op_beats)
    export_midi(NOTES, expression, events, total_beats, duration_s)

    v.HERE = HERE
    v.DURATION = duration_s
    v.PARTS = PARTS
    v.NOTES = NOTES
    v.curve = expression

    n = round(duration_s * SR)
    mix = np.zeros((n, 2), np.float32)
    send = np.zeros_like(mix)
    gr = {}
    for name in PARTS:
        print('Render', name, flush=True)
        a, amount, gr[name] = v.render(name, True)
        mix += a
        send += a * amount

    ir = v.make_reverb_ir()
    for side in range(2):
        mix[:, side] += signal.fftconvolve(send[:, side], ir, mode='full')[:n]

    t = np.arange(n) / SR
    mix *= np.minimum(np.clip(t / 0.08, 0, 1), np.clip((duration_s - t) / 1.5, 0, 1))[:, None]
    mix *= min(1.0, 0.85 / max(np.max(np.abs(mix)), 1e-6))

    premix = v.RT / 'v6-premix.wav'
    v.writewav(premix, mix)
    m = v.loudness(premix)
    gain_db = min(-20.5 - m['input_i'], -1.7 - m['input_tp'])
    gain_lin = 10 ** (gain_db / 20)
    mix_norm = np.clip(mix * gain_lin, -0.99, 0.99)

    out_full = HERE / '开场至第二段_完整_v6.wav'
    subprocess.run([FF, '-y', '-v', 'error', '-i', str(premix), '-af', f'volume={gain_db}dB', '-c:a', 'pcm_s24le', str(out_full)], check=True)
    subprocess.run([FF, '-y', '-v', 'error', '-i', str(out_full), '-c:a', 'libmp3lame', '-b:a', '224k', str(out_full.with_suffix('.mp3'))], check=True)

    # Also export 3 clean theme slices + 2.5m highlight sampler
    SEC = dict(sections)
    t_b1 = op_beats * BEAT_S
    t_b2 = SEC['city_enter'] * BEAT_S
    slices = [
        ('01_开场_主题A_v6.wav', 0.0, t_b1),
        ('02_第二段前半_进镜潮前_主题B1_v6.wav', t_b1, t_b2),
        ('03_第二段后半_进镜潮后_主题B2_v6.wav', t_b2, duration_s),
    ]
    for fname, s0, s1 in slices:
        seg = mix_norm[int(s0 * SR): int(s1 * SR)].copy()
        fade = int(0.05 * SR)
        if s0 > 0:
            seg[:fade] *= np.linspace(0, 1, fade, dtype=np.float32)[:, None]
        seg[-fade:] *= np.linspace(1, 0, fade, dtype=np.float32)[:, None]
        tmp_seg = v.RT / f'seg-{fname}'
        v.writewav(tmp_seg, seg)
        out_seg = HERE / fname
        subprocess.run([FF, '-y', '-v', 'error', '-i', str(tmp_seg), '-c:a', 'pcm_s24le', str(out_seg)], check=True)

    write_highlight_sampler(mix_norm, op_beats, sections)

    report = {
        'bpm': BPM,
        'duration_s': round(duration_s, 3),
        'total_beats': total_beats,
        'sections': [{'name': k, 'beat': b, 'time_s': round(b * BEAT_S, 3)} for k, b in sections],
        'loudness': v.loudness(out_full),
        'compression_max_db': gr,
        'note_counts': {k: len(x) for k, x in NOTES.items()},
    }
    (HERE / 'render_report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf8')
    print('Done! Loudness:', report['loudness'], 'Notes:', report['note_counts'])


if __name__ == '__main__':
    main()
