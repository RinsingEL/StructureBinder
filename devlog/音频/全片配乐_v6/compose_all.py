"""Original v6 complete 5-act soundtrack composer & renderer (Act 1 -> Act 5, ~37m40s @ 108 BPM).

Themes:
  - Theme A  (Act 1 Opening, 0..228 beats, D major): Warm clubroom afternoon tea party.
  - Theme B1 (Act 2 Part 1, 228..700 beats, D major): World blank canvas, 90k scan, seas, 7 nations.
  - Theme B2 (Act 2 Part 2, 700..1812 beats, G major): Mirror Tide campus, D4 twist, Observatory duet.
  - Theme A' (Act 3 Recap, 1812..2904 beats, D major): Nostalgic warm return to Act 1's clubroom origin.
  - Theme C  (Act 4 Future + Act 5 Farewell, 2904..4068 beats, A major / F#m): Horizons, night patrol, gears, Polaroid finale.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import math
import subprocess
from pathlib import Path

import numpy as np
from scipy import signal

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]

spec_r = importlib.util.spec_from_file_location('renderer_v6', HERE / 'renderer.py')
v = importlib.util.module_from_spec(spec_r)
spec_r.loader.exec_module(v)

spec_m = importlib.util.spec_from_file_location('motifs_v6', HERE / 'motifs.py')
motifs = importlib.util.module_from_spec(spec_m)
spec_m.loader.exec_module(motifs)

spec_12 = importlib.util.spec_from_file_location('compose_act1_2_v6', HERE / 'compose_act1_2.py')
c12 = importlib.util.module_from_spec(spec_12)
spec_12.loader.exec_module(c12)

SR = v.SR
FF = v.FF
BPM = c12.BPM
BEAT_S = c12.BEAT_S
WHO = c12.WHO
PARTS = {**c12.PARTS, 'quiet_percussion': (40, 9, 0.08, -30.5, 0.04)}

# Extend chord palette cleanly with A-major / F#m harmony for Theme C (Acts 4 & 5)
CHORDS = {
    **c12.CHORDS,
    'A_a':     (33, 40, [57, 61, 64, 71]),   # Aadd9 (A1 -> E2)
    'E/Gs':    (44, 40, [56, 59, 64, 71]),   # E/G#  (G#2 -> E2, G#3 keeps A4 melody clear)
    'Fsm7_a':  (42, 37, [57, 61, 64, 66]),   # F#m7  (F#2 -> C#2)
    'D_a':     (38, 45, [54, 57, 62, 66]),   # D     (D2 -> A2)
    'A/Cs_a':  (37, 45, [52, 57, 61, 64]),   # A/C#  (C#2 -> A2)
    'Bm7_a':   (35, 42, [57, 59, 62, 66]),   # Bm7   (B1 -> F#2)
    'Csm7':    (37, 44, [56, 59, 61, 64]),   # C#m7  (C#2 -> G#2)
    'E7sus':   (40, 47, [57, 59, 64, 71]),   # E9sus4(E2 -> B2)
    'E_a':     (40, 47, [56, 59, 64, 71]),   # E     (E2 -> B2)
    'Dmaj7_a': (38, 45, [54, 57, 61, 66]),   # Dmaj7 (D2 -> A2, zero m2 crunch)
    'Dm6_a':   (38, 45, [57, 60, 62, 69]),   # Dm6   (D2 -> A2, gentle iv6 color)
}


def load_all_timelines():
    acts = {}
    act_offsets = {}
    cursor_beats = 0
    for name in ['开场', '第二段', '第三段', '第四段', '第五段']:
        d = json.loads((HERE / f'timeline_{name}.json').read_text(encoding='utf8'))
        beats_len = round(d['duration'] / BEAT_S)
        acts[name] = d
        act_offsets[name] = (cursor_beats, beats_len)
        cursor_beats += beats_len

    total_beats = cursor_beats
    duration_s = total_beats * BEAT_S

    # Reuse exact Act 1 + Act 2 events & sections from compose_act1_2
    _, _, op_beats, _, _, ev12, sec12 = c12.load_timelines()
    events = list(ev12)

    # Extract Act 3, 4, 5 speaker turn / mark events cleanly
    for act_name in ('第三段', '第四段', '第五段'):
        off, _ = act_offsets[act_name]
        d = acts[act_name]
        mark_by_beat = {}
        for mk, mt in d.get('marks', {}).items():
            mark_by_beat.setdefault(round(mt / BEAT_S, 4), []).append(mk)

        prev_who = None
        for c in d['cards']:
            is_line_start = abs(c['start'] - c['lineStart']) < 1e-6
            b_abs = off + round(c['start'] / BEAT_S, 4)
            rel_b = round(c['start'] / BEAT_S, 4)
            mks = mark_by_beat.get(rel_b, [])
            kind = mks[0] if mks else ('line' if is_line_start else None)
            if (is_line_start and (c['who'] != prev_who or mks)) or mks:
                events.append({
                    'id': c['id'],
                    'who': c['who'],
                    'beat': b_abs,
                    'hit': b_abs * BEAT_S,
                    'kind': kind or 'speaker',
                    'all_marks': mks,
                })
            if is_line_start:
                prev_who = c['who']

    def mark_abs(act_name, key):
        off, _ = act_offsets[act_name]
        return off + round(acts[act_name]['marks'][key] / BEAT_S, 4)

    a3_off, a3_len = act_offsets['第三段']
    a4_off, a4_len = act_offsets['第四段']
    a5_off, a5_len = act_offsets['第五段']

    sections = list(sec12) + [
        ('act3_open',      float(a3_off)),
        ('act3_tl',        mark_abs('第三段', 'tl')),
        ('act3_trials',    mark_abs('第三段', 'methods')),
        ('act3_sept',      mark_abs('第三段', 'n2609')),
        ('act3_d4',        mark_abs('第三段', 'n2610')),
        ('act3_rewrite',   mark_abs('第三段', 'rewrite')),
        ('act3_tlall',     mark_abs('第三段', 'tlall')),
        ('act4_open',      float(a4_off)),
        ('act4_coast',     mark_abs('第四段', 'harbor')),
        ('act4_day',       mark_abs('第四段', 'day')),
        ('act4_night',     mark_abs('第四段', 'night')),
        ('act4_gears',     mark_abs('第四段', 'wish')),
        ('act4_adventure', mark_abs('第四段', 'valley')),
        ('act4_finale',    mark_abs('第四段', 'finale')),
        ('act5_thanks',    float(a5_off)),
        ('act5_bye',       mark_abs('第五段', 'bye')),
    ]
    return acts, act_offsets, op_beats, total_beats, duration_s, events, sections


def make_full_score(op_beats, act_offsets, total_beats, duration_s, events, sections, latencies):
    # Generate Act 1 + Act 2 (beats 0..1812) identically via compose_act1_2, then trim any note past 1812
    a3_start = act_offsets['第三段'][0]
    a3_start_s = a3_start * BEAT_S
    NOTES12, ENV12 = c12.make_score(op_beats, a3_start, a3_start_s, [e for e in events if e['beat'] < a3_start],
                                    [s for s in sections if s[1] < a3_start], latencies)
    NOTES = {k: [(t, p, d, v_) for (t, p, d, v_) in lst if t < a3_start_s - 0.02] for k, lst in NOTES12.items()}
    ENV = {k: list(lst) for k, lst in ENV12.items()}

    SEC = dict(sections)

    def sec_at(b):
        return next(k for k, start in reversed(sections) if b >= start - 1e-6)

    def theme_at(b):
        if b < SEC['act4_open']:
            return 'A_prime'
        return 'C'

    def key_tonic(b):
        return 62 if theme_at(b) == 'A_prime' else 69  # D4 vs A4

    def harmony(b):
        k = sec_at(b)
        if theme_at(b) == 'A_prime':
            # Warm plagal return from Act 2 G major into D major on opening 4 bars
            if k == 'act3_open' and b < SEC['act3_open'] + 16:
                return ['Gmaj7', 'Em7', 'A7sus', 'D'][int((b - SEC['act3_open']) // 4)]
            # Smooth V/V -> V pivot in final 2 bars of Act 3 into Act 4's A major
            if b >= SEC['act4_open'] - 8:
                return 'E7sus' if b < SEC['act4_open'] - 4 else 'E_a'
            if k in ('act3_d4', 'act3_rewrite'):
                # Emotional D4 confession & rewrite curve (continuous chord cycle across the rewrite turn)
                rel = int((b - SEC['act3_d4']) // 4)
                prog = ['Bm7', 'Fsm7', 'Gmaj7', 'D/Fs', 'Em7', 'Gm6', 'Em7', 'A7sus',
                        'D', 'A/Cs', 'Bm7', 'Fsm7', 'Gmaj7', 'D/Fs', 'Em7', 'A']
                return prog[rel % 16]
            prog = ['D', 'A/Cs', 'Bm7', 'Fsm7', 'Gmaj7', 'D/Fs', 'Em7', 'A7sus',
                    'D', 'A/Cs', 'Bm7', 'Fsm7', 'Gmaj7', 'D/Fs', 'Em7', 'A']
            return prog[int((b - SEC['act3_open']) // 4) % 16]

        # Theme C (Act 4 + Act 5, A major / F#m night tint)
        if k == 'act4_night':
            prog = ['Fsm7_a', 'Dmaj7_a', 'Bm7_a', 'E7sus', 'Fsm7_a', 'Csm7', 'Bm7_a', 'E7sus']
            return prog[int((b - SEC['act4_night']) // 4) % 8]
        if k == 'act4_gears':
            prog = ['A_a', 'E/Gs', 'Fsm7_a', 'Csm7', 'D_a', 'A/Cs_a', 'Bm7_a', 'E_a']
            return prog[int((b - SEC['act4_gears']) // 4) % 8]
        if k == 'act4_finale':
            prog = ['Dmaj7_a', 'A/Cs_a', 'Bm7_a', 'E7sus', 'Dmaj7_a', 'E7sus']
            return prog[min(5, int((b - SEC['act4_finale']) // 4))]
        if k == 'act5_bye':
            rel = int((b - SEC['act5_bye']) // 4)
            return ['Dmaj7_a', 'E7sus', 'A_a', 'A_a'][min(3, rel)]
        if k == 'act5_thanks':
            prog = ['A_a', 'E/Gs', 'Fsm7_a', 'Dmaj7_a', 'A/Cs_a', 'Bm7_a', 'Dmaj7_a', 'E7sus']
            return prog[int((b - SEC['act5_thanks']) // 4) % 8]
        prog = ['A_a', 'E/Gs', 'Fsm7_a', 'D_a', 'A/Cs_a', 'Bm7_a', 'E7sus', 'A_a',
                'A_a', 'E/Gs', 'Fsm7_a', 'Csm7', 'Dmaj7_a', 'Bm7_a', 'E7sus', 'E_a']
        return prog[int((b - SEC['act4_open']) // 4) % 16]

    def add(part, b, p, dur_beats, vel, latency=0.0):
        t = max(a3_start_s, b * BEAT_S - latency)
        end = (b + dur_beats) * BEAT_S - latency
        if t < duration_s - 0.5:
            NOTES[part].append((t, int(p), max(0.07, end - t), int(np.clip(vel, 20, 118))))

    def add_env(part, b_start, b_end, strength=0.88, latency=0.0):
        t = max(a3_start_s, b_start * BEAT_S - latency)
        end = b_end * BEAT_S - latency
        ENV[part].append({
            'times': [max(a3_start_s, t - 0.14), t, t + 0.25, (t + end) * 0.5, max(t + 0.26, end - 0.10), end + 0.45],
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
                if part == 'glm_vibes' and support:
                    raw_p += 12
                p = motifs.snap_to_harmony(raw_p, chord_tones, tonic_pc, chord_sym)
                add(part, b, p, dur_clamped, round(vel * scale_vel), latency)
            used.append((off, dur_clamped))
        if used:
            end_b = start_b + max(off + d for off, d in used)
            add_env(part, start_b, end_b, 0.38 if support else 0.90, latency)

    # 1. Accompaniment Grid for Acts 3, 4, 5 (strict 4-beat bars)
    first_bar = a3_start // 4
    total_bars = math.ceil(total_beats / 4)
    for bar in range(first_bar, total_bars):
        base = bar * 4
        if base >= total_beats - 1:
            break
        k = sec_at(base)
        sym = harmony(base)
        root, alt_bass, ch = CHORDS[sym]

        # Guitar patterns shaped by Act 3–5 emotional arc
        if k == 'act5_bye':
            # Final Polaroid fade: hold only first two bars gently, fade on Aadd9
            if base < SEC['act5_bye'] + 8:
                for idx, p in enumerate(ch):
                    add('shared_guitar', base + idx * 0.18, p, 3.6, 40 - idx * 2)
                add('shared_bass', base, root, 3.6, 44)
                for p in ch[1:]:
                    add('soft_pad', base, p, 3.8, 38)
            continue

        is_break_bar = (k in ('act3_tl', 'act3_trials') and bar % 8 == 7)

        if k in ('act3_rewrite', 'act3_tlall', 'act4_finale', 'act5_thanks'):
            # Luminous rising 1-2-3-4 arpeggio (D4 rewrite dawn, Act 3 wrap-up, Act 4 sunset -> Act 5 farewell)
            pat = {0.0: (0, 1.35, 43), 1.0: (1, 1.1, 38), 2.0: (2, 1.2, 41), 3.0: (3, 1.0, 37)}
        elif k == 'act3_open':
            pat = {0.0: (0, 1.5, 42), 1.5: (2, 0.9, 37), 2.5: (1, 1.0, 39)}
        elif k == 'act3_d4':
            # Deep half-time breath for the 2610 D4 reflection & letting go
            pat = {0.0: (0, 2.2, 42), 2.0: (2, 1.8, 37)}
        elif k in ('act3_tl', 'act3_trials'):
            # Theme A' trials: 2-bar A/B call-and-response + subtle 8-bar breath drop-out
            if is_break_bar:
                pat = {0.0: (0, 2.2, 42), 2.0: (2, 1.6, 36)}
            elif bar % 2 == 0:
                pat = {0.0: (0, 0.9, 44), 1.5: (2, 0.7, 38), 2.5: (1, 0.85, 41), 3.5: (3, 0.4, 36)}
            else:
                pat = {0.0: (0, 1.35, 42), 2.0: (2, 1.25, 38), 3.0: (1, 0.9, 37)}
        elif k == 'act3_sept':
            # September milestone (2609) team sprint: steady 1-2-3-4 step forward
            pat = {0.0: (0, 0.85, 46), 1.0: (2, 0.75, 41), 2.0: (1, 0.85, 44), 3.0: (3, 0.75, 40)}
        elif k == 'act4_night':
            # Gentle nocturnal shimmer
            pat = {0.0: (0, 2.2, 40), 2.0: (2, 1.6, 36)}
            if bar % 2 == 0:
                add('glm_vibes', base + 1.0, ch[2] + 12, 1.2, 34)
                add('glm_vibes', base + 3.0, ch[3] + 12, 1.2, 32)
                add_env('glm_vibes', base + 1.0, base + 4.0, 0.36)
        elif k == 'act4_gears':
            # Crisp mechanical gears & craftsman steps
            pat = {0.0: (0, 0.75, 46), 1.0: (2, 0.7, 41), 2.0: (1, 0.75, 43), 3.0: (3, 0.7, 39)}
        elif k == 'act4_coast':
            # Sunlit coastal Theme C: 2-bar 3+3+2 stride answered by smooth 1-2-3-4
            if bar % 2 == 0:
                pat = {0.0: (0, 1.4, 46), 1.5: (2, 1.3, 42), 3.0: (1, 1.0, 43)}
            else:
                pat = {0.0: (0, 1.2, 45), 1.0: (2, 0.9, 40), 2.0: (1, 1.1, 43), 3.0: (3, 0.9, 39)}
        elif k == 'act4_day':
            # Calm daytime A/B stroll leading gently toward nightfall
            if bar % 2 == 0:
                pat = {0.0: (0, 1.15, 44), 1.5: (2, 0.85, 39), 2.5: (1, 0.95, 41), 3.5: (3, 0.5, 37)}
            else:
                pat = {0.0: (0, 1.6, 42), 2.0: (2, 1.4, 38)}
        elif k == 'act4_adventure':
            # Solid quarter-note adventure drive locked with Kick + Snap (no choppy offbeat gaps)
            pat = {0.0: (0, 1.2, 48), 1.0: (2, 0.95, 42), 2.0: (1, 1.15, 46), 3.0: (3, 0.95, 42)}
        else:
            pat = {0.0: (0, 1.1, 43), 1.5: (2, 0.75, 38), 2.5: (1, 0.9, 40), 3.5: (3, 0.4, 36)}

        for bt, (idx, d, vel) in pat.items():
            add('shared_guitar', base + bt, ch[idx], d, vel)

        # Bass
        if k in ('act3_d4', 'act3_rewrite', 'act3_tlall', 'act5_thanks', 'act4_night', 'act4_finale') or is_break_bar:
            add('shared_bass', base, root, 3.4 if k == 'act3_d4' else 3.2, 46)
        elif k == 'act4_adventure':
            # Full adventure anchor on beats 1 & 3
            add('shared_bass', base, root, 1.9, 58)
            add('shared_bass', base + 2.0, alt_bass, 1.8, 50)
        elif k == 'act3_sept':
            add('shared_bass', base, root, 1.85, 54)
            add('shared_bass', base + 2.0, alt_bass, 1.6, 45)
        else:
            add('shared_bass', base, root, 1.8, 52)
            add('shared_bass', base + 2.0, alt_bass, 1.5, 42)

        # Subtle percussion (kept clean and absent in intimate/nocturnal/half-time sections)
        if k in ('act3_tl', 'act3_trials') and not is_break_bar:
            if bar % 2 == 0:
                add('quiet_percussion', base + 1.0, 37, 0.10, 23)
                add('quiet_percussion', base + 1.0, 42, 0.09, 19)
                add('quiet_percussion', base + 3.0, 37, 0.10, 23)
                add('quiet_percussion', base + 3.0, 42, 0.09, 19)
        elif k == 'act3_sept':
            # Gentle Brush sprint step (mid-arc: downbeat-only soft Kick every bar + crisp Sidestick/Brush + quarter Hats, saving full Kick climax for Act 4)
            add('quiet_percussion', base + 0.0, 35, 0.16, 12)
            add('quiet_percussion', base + 1.0, 37, 0.11, 26)
            add('quiet_percussion', base + 1.0, 38, 0.15, 34)
            add('quiet_percussion', base + 3.0, 37, 0.11, 26)
            add('quiet_percussion', base + 3.0, 38, 0.15, 34)
            for sub in (0.0, 1.0, 2.0, 3.0):
                add('quiet_percussion', base + sub, 42, 0.08, 21 if sub in (0.0, 2.0) else 16)
        elif k == 'act4_gears':
            for bt in (0.0, 1.0, 2.0, 3.0):
                add('quiet_percussion', base + bt, 42 if bt in (1, 3) else 37, 0.09, 25 if bt in (0, 2) else 20)
        elif k == 'act4_coast':
            # Open coastal footsteps: warm Sidestick (37) on 2 & 4 + quarter Brush Hats (no lonely offbeat chopping)
            add('quiet_percussion', base + 1.0, 37, 0.11, 25)
            add('quiet_percussion', base + 3.0, 37, 0.11, 25)
            for sub in (0.0, 1.0, 2.0, 3.0):
                add('quiet_percussion', base + sub, 42, 0.08, 19 if sub in (0.0, 2.0) else 15)
        elif k == 'act4_day':
            # Gentle daytime breeze on even bars before fading into drumless night
            if bar % 2 == 0:
                add('quiet_percussion', base + 1.0, 37, 0.10, 21)
                add('quiet_percussion', base + 1.0, 42, 0.08, 17)
                add('quiet_percussion', base + 3.0, 37, 0.10, 21)
                add('quiet_percussion', base + 3.0, 42, 0.08, 17)
        elif k == 'act4_adventure':
            # Original Brush kit (prog 40): Kick (35) on 1 & 3 + Sidestick/Brush Snare (37 + 38) on 2 & 4 + light Hats (42) tying the bar together
            add('quiet_percussion', base + 0.0, 35, 0.20, 17)
            add('quiet_percussion', base + 1.0, 37, 0.12, 30)
            add('quiet_percussion', base + 1.0, 38, 0.18, 42)
            add('quiet_percussion', base + 2.0, 35, 0.20, 15)
            add('quiet_percussion', base + 3.0, 37, 0.12, 30)
            add('quiet_percussion', base + 3.0, 38, 0.18, 42)
            for sub in (0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5):
                add('quiet_percussion', base + sub, 42, 0.08, 22 if sub in (0.0, 1.0, 2.0, 3.0) else 15)

        # Act 5 Roll-Call Ensemble Layering: after each partner speaks their thank-you (5-1..5-6),
        # they stay seated in a dedicated non-overlapping register/beat pocket until everyone plays together.
        if k == 'act5_thanks' and SEC['act5_thanks'] + 16 <= base < SEC['act5_bye']:
            if base >= 3944:  # After 5-1 Rin: soft piano chime on beat 1
                add('rin_piano', base + 0.0, ch[2] + 12, 1.8, 35)
                add_env('rin_piano', base, base + 2.2, 0.40)
            if base >= 3960 and bar % 2 == 0:  # After 5-2 Claude: warm low-mid clarinet sustain on even bars
                add('claude_clarinet', base + 0.0, ch[0], 3.5, 36, latencies['claude_clarinet'])
                add_env('claude_clarinet', base, base + 3.8, 0.42, latencies['claude_clarinet'])
            if base >= 3992 and bar % 2 == 1:  # After 5-3 吉酱/Gemini: airy high flute breath on odd bars
                add('gemini_flute', base + 2.0, ch[3] + 12, 2.5, 37, latencies['gemini_flute'])
                add_env('gemini_flute', base + 1.8, base + 4.4, 0.42, latencies['gemini_flute'])
            if base >= 4012:  # After 5-4 DeepSeek: delicate right-side pizzicato stars on offbeats
                add('deepseek_pizz', base + 1.5, ch[1] + 12, 0.25, 35, latencies['deepseek_pizz'])
                add('deepseek_pizz', base + 3.5, ch[2] + 12, 0.25, 33, latencies['deepseek_pizz'])
                add_env('deepseek_pizz', base + 1.3, base + 3.9, 0.38, latencies['deepseek_pizz'])
            if base >= 4032:  # After 5-5 GLM: sparkling upper-register vibes touches on beats 2 & 4
                add('glm_vibes', base + 1.0, ch[2] + 12, 1.3, 34)
                add('glm_vibes', base + 3.0, ch[3] + 12, 1.3, 34)
                add_env('glm_vibes', base + 0.8, base + 4.2, 0.40)

        # Warm pad swells
        if k in ('act3_open', 'act3_d4', 'act3_rewrite', 'act3_tlall', 'act4_night', 'act4_adventure', 'act4_finale', 'act5_thanks') and (bar % 2 == 0 or k in ('act3_d4', 'act4_night', 'act4_finale', 'act5_thanks')):
            for p in ch[1:]:
                add('soft_pad', base, p, 3.8, 36 if k in ('act4_night', 'act3_d4') else (30 if k == 'act4_adventure' else 33))

    # 2. Dialogue Ensemble & Anchored Themes across Acts 3, 4, 5
    a3_anchors = {'sea', 'tl', 'n2606', 'rewrite', 'tlall'}
    a4_anchors = {'harbor', 'day', 'gears', 'valley', 'clues', 'finale'}
    hard_stop_34 = a3_anchors | a4_anchors | {'bye'}
    last_solo_beat = {}
    relay = None  # (anchor_beat, anchor_theme, anchor_end) for 8-bar relay duets across 2 speakers
    anchor_end_block = None  # end beat of current 32-beat anchor so incoming phrases wait cleanly for Bar 9 downbeat

    for i, e in enumerate(events):
        b = e['beat']
        if b < a3_start:
            continue
        who = e['who']
        b_all = (who == 'all')
        part = 'rin_piano' if b_all else WHO[who]
        next_b = events[i + 1]['beat'] if i + 1 < len(events) else total_beats
        avail = next_b - b
        th = theme_at(b)

        # Act 5 Final Polaroid Goodbye ("大家再见!") -> all 6 clubroom partners gather on one luminous Aadd9 cadence
        if e.get('kind') == 'bye' or b >= SEC['act5_bye']:
            relay = None
            if e.get('kind') == 'bye':
                add('claude_clarinet', b + 0.0, 64, 5.5, 38, latencies['claude_clarinet'])
                add_env('claude_clarinet', b, b + 6.5, 0.48, latencies['claude_clarinet'])
                for p in (57, 61, 71):
                    add('astra_epiano', b + 1.0, p, 6.2, 40)
                add_env('astra_epiano', b + 0.8, b + 7.5, 0.50)
                for off, p, d, vel in [(0.0, 69, 2.2, 54), (1.0, 73, 2.0, 56), (2.0, 76, 3.0, 58), (4.0, 83, 6.0, 52)]:
                    add('rin_piano', b + off, p, d, vel)
                add_env('rin_piano', b, b + 10.0, 0.85)
                for off, p, d, vel in [(2.0, 76, 0.35, 40), (4.0, 81, 0.45, 42)]:
                    add('deepseek_pizz', b + off, p, d, vel, latencies['deepseek_pizz'])
                add_env('deepseek_pizz', b + 1.8, b + 5.0, 0.50, latencies['deepseek_pizz'])
                for off, p, d, vel in [(2.0, 76, 3.5, 46), (4.0, 81, 5.5, 48), (5.0, 83, 5.5, 44)]:
                    add('glm_vibes', b + off, p, d, vel)
                add_env('glm_vibes', b + 2.0, b + 10.5, 0.72)
                for off, p, d, vel in [(4.0, 73, 3.5, 45), (6.0, 76, 4.5, 43)]:
                    add('gemini_flute', b + off, p, d, vel, latencies['gemini_flute'])
                add_env('gemini_flute', b + 4.0, b + 10.5, 0.76, latencies['gemini_flute'])
            continue

        # Act 3 Anchor statements -> full 8-bar Theme A' piano when clear, relaying smoothly across 2 speakers
        if th == 'A_prime' and e.get('kind') in a3_anchors:
            next_hard = next((ev['beat'] for ev in events[i + 1:] if ev.get('kind') in hard_stop_34 or ev['beat'] >= SEC['act4_open']), SEC['act4_open'])
            theme_len = 32.0 if (next_hard - b >= 32.0) else 16.0
            piano_scale = 0.80 if part != 'rin_piano' else 0.90
            render_motif('rin_piano', b, theme_len, motifs.THEME_A_PRIME, piano_scale, latencies['rin_piano'])
            last_solo_beat['rin_piano'] = b
            relay = (b, 'A_prime', b + theme_len) if (theme_len >= 32.0 and avail < 26.0) else None
            anchor_end_block = b + theme_len if theme_len >= 32.0 else None
            if part == 'rin_piano' and theme_len >= 32.0:
                p2_gem = motifs.ANCHOR_DUETS_P2['A_prime']['gemini_flute']
                render_motif('gemini_flute', b, 32.0, p2_gem, 0.72, latencies['gemini_flute'])
                last_solo_beat['gemini_flute'] = b + 16.0
            elif part != 'rin_piano':
                duet_line = motifs.ANCHOR_DUETS['A_prime'].get(part)
                if duet_line:
                    render_motif(part, b, 16.0, duet_line, 0.86, latencies[part])
                    last_solo_beat[part] = b
                if theme_len >= 32.0 and avail >= 26.0:
                    p2_same = motifs.ANCHOR_DUETS_P2['A_prime'].get(part)
                    if p2_same:
                        render_motif(part, b, 32.0, p2_same, 0.84, latencies[part])
                        last_solo_beat[part] = b + 16.0
                if avail >= 36.0:
                    ap_vars = motifs.A_PRIME_CONVERSATIONS[part]
                    render_motif(part, b + 32.0, min(6.5, avail - 33.5), ap_vars[i % len(ap_vars)], 0.80, latencies[part])
                    last_solo_beat[part] = b + 32.0
            continue

        # Act 4 Anchor statements -> full 8-bar Theme C piano when clear, relaying smoothly across 2 speakers
        if th == 'C' and b < SEC['act5_thanks'] and (e['id'] == '4-1' or e.get('kind') in a4_anchors):
            next_hard = next((ev['beat'] for ev in events[i + 1:] if ev.get('kind') in hard_stop_34 or ev['beat'] >= SEC['act5_thanks']), SEC['act5_thanks'])
            theme_len = 32.0 if (next_hard - b >= 32.0 and e.get('kind') != 'finale') else 16.0
            piano_scale = 0.82 if part != 'rin_piano' else 0.92
            render_motif('rin_piano', b, theme_len, motifs.THEME_C_ADVENTURE, piano_scale, latencies['rin_piano'])
            last_solo_beat['rin_piano'] = b
            relay = (b, 'C', b + theme_len) if (theme_len >= 32.0 and avail < 26.0 and e.get('kind') != 'finale') else None
            anchor_end_block = (b + theme_len) if (theme_len >= 32.0 and e.get('kind') != 'finale') else None
            if e.get('kind') == 'finale':
                render_motif('gemini_flute', b + 8.0, 14.0, motifs.C_CONVERSATIONS['gemini_flute'][0], 0.84, latencies['gemini_flute'])
            elif part == 'rin_piano' and theme_len >= 32.0:
                p2_vibes = motifs.ANCHOR_DUETS_P2['C']['glm_vibes']
                render_motif('glm_vibes', b, 32.0, p2_vibes, 0.72, latencies['glm_vibes'])
                last_solo_beat['glm_vibes'] = b + 16.0
            elif part != 'rin_piano':
                duet_line = motifs.ANCHOR_DUETS['C'].get(part)
                if duet_line:
                    render_motif(part, b, 16.0, duet_line, 0.86, latencies[part])
                    last_solo_beat[part] = b
                if theme_len >= 32.0 and avail >= 26.0:
                    p2_same = motifs.ANCHOR_DUETS_P2['C'].get(part)
                    if p2_same:
                        render_motif(part, b, 32.0, p2_same, 0.84, latencies[part])
                        last_solo_beat[part] = b + 16.0
                if avail >= 36.0:
                    c_vars = motifs.C_CONVERSATIONS[part]
                    render_motif(part, b + 32.0, min(6.5, avail - 33.5), c_vars[i % len(c_vars)], 0.82, latencies[part])
                    last_solo_beat[part] = b + 32.0
            continue

        # Relay speaker joining Bars 5-8 of an active 8-bar anchor theme cleanly without interrupting Piano
        if relay is not None and b < relay[2] - 4.0:
            a_b, a_th, a_end = relay
            relay = None
            if part != 'rin_piano' and b >= a_b + 11.0 and th == a_th and b < SEC['act5_thanks']:
                p2 = motifs.ANCHOR_DUETS_P2[a_th].get(part)
                if p2:
                    render_motif(part, a_b, 32.0, p2, 0.86, latencies[part])
                    last_solo_beat[part] = a_b + 16.0
                tail_start = max(a_end + 1.0, b + 20.0)
                if b + avail >= tail_start + 6.0:
                    conv = motifs.A_PRIME_CONVERSATIONS if th == 'A_prime' else motifs.C_CONVERSATIONS
                    render_motif(part, tail_start, min(6.5, b + avail - tail_start - 1.5), conv[part][(i + 1) % len(conv[part])], 0.78, latencies[part])
                    last_solo_beat[part] = tail_start
                continue
        relay = None
        if anchor_end_block is not None and b < anchor_end_block - 0.5:
            if b + avail <= anchor_end_block + 5.0:
                continue
            b = anchor_end_block
            avail = next_b - b
        anchor_end_block = None

        # Normal dialogue phrases (Theme A' or Theme C), keeping cozy breathing space
        conv_bank = motifs.A_PRIME_CONVERSATIONS if th == 'A_prime' else motifs.C_CONVERSATIONS
        echo_bank = motifs.A_PRIME_SUPPORT_ECHOES if th == 'A_prime' else motifs.C_SUPPORT_ECHOES
        is_act5 = b >= SEC['act5_thanks']
        vel_scale = 0.82 if is_act5 else 0.86

        if b - last_solo_beat.get(part, -999.0) >= (4.0 if is_act5 else 8.0):
            phrases = conv_bank[part]
            phrase_len = min(6.5, max(2.5, avail - 1.0))
            render_motif(part, b, phrase_len, phrases[i % len(phrases)], vel_scale, latencies[part])
            last_solo_beat[part] = b
            if avail >= 22 and not is_act5:
                render_motif(part, b + 10.0, min(6.0, avail - 12.0), phrases[(i + 1) % len(phrases)], 0.78, latencies[part])
            if avail >= 36 and not is_act5:
                render_motif(part, b + 24.0, min(6.0, avail - 26.0), phrases[(i + 1) % len(phrases)], 0.74, latencies[part])

        if i > 0 and avail >= 16 and events[i - 1]['who'] not in (who, 'all'):
            prev_part = WHO[events[i - 1]['who']]
            render_motif(prev_part, b + 6.0, 4.5, echo_bank[prev_part], 0.70, latencies[prev_part], support=True)

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


def write_full_sampler(mix_norm, sections):
    SEC = dict(sections)
    slices = [
        ('开场 主题A', 0.0, 36.0),
        ('W2扫描 主题B1', SEC['scan'] * BEAT_S - 1.0, SEC['scan'] * BEAT_S + 26.0),
        ('T4进镜潮 主题B2', SEC['city_enter'] * BEAT_S - 6.0, SEC['city_enter'] * BEAT_S + 28.0),
        ('天象台二重奏', SEC['observatory'] * BEAT_S - 2.0, SEC['observatory'] * BEAT_S + 26.0),
        ('第三段 回顾 主题Ap', SEC['act3_open'] * BEAT_S - 4.0, SEC['act3_open'] * BEAT_S + 28.0),
        ('第四段 展望 主题C', SEC['act4_coast'] * BEAT_S - 2.0, SEC['act4_coast'] * BEAT_S + 26.0),
        ('第五段 再见定格', SEC['act5_thanks'] * BEAT_S + 32.0, SEC['act5_bye'] * BEAT_S + 7.5),
    ]
    fade = int(0.45 * SR)
    chunks = []
    for idx, (_, t0, t1) in enumerate(slices):
        seg = mix_norm[max(0, int(t0 * SR)): min(len(mix_norm), int(t1 * SR))].copy()
        if idx > 0:
            seg[:fade] *= np.linspace(0, 1, fade, dtype=np.float32)[:, None]
        if idx < len(slices) - 1:
            seg[-fade:] *= np.linspace(1, 0, fade, dtype=np.float32)[:, None]
        chunks.append(seg)
    sampler = np.clip(np.concatenate(chunks, axis=0), -0.99, 0.99)
    tmp = v.RT / 'v6-full-sampler.wav'
    v.writewav(tmp, sampler)
    out = HERE / '00_全片精华串烧_v6.wav'
    subprocess.run([FF, '-y', '-v', 'error', '-i', str(tmp), '-c:a', 'pcm_s24le', str(out)], check=True)
    subprocess.run([FF, '-y', '-v', 'error', '-i', str(out), '-c:a', 'libmp3lame', '-b:a', '224k', str(out.with_suffix('.mp3'))], check=True)


def main():
    parser = argparse.ArgumentParser(description='Compose & render complete 5-act v6 soundtrack.')
    parser.add_argument('--soundfont', type=Path)
    args = parser.parse_args()
    if args.soundfont is not None:
        v.SF = args.soundfont.expanduser().resolve()
    if not v.SF.is_file():
        parser.error('SoundFont missing: ' + str(v.SF))
    v.RT.mkdir(parents=True, exist_ok=True)

    _, act_offsets, op_beats, total_beats, duration_s, events, sections = load_all_timelines()
    latencies = c12.profile_latency()
    print('Total beats:', total_beats, f'({duration_s:.2f}s)', flush=True)

    NOTES, ENV = make_full_score(op_beats, act_offsets, total_beats, duration_s, events, sections, latencies)
    expression = c12.build_expression(ENV, events, duration_s, op_beats)

    # Export full 5-act MIDI (temporarily write to 全片配乐_v6.mid)
    mid_backup = HERE / '开场至第二段_v6.mid'
    c12.export_midi(NOTES, expression, events, total_beats, duration_s)
    mid_backup.replace(HERE / '全片配乐_v6.mid')

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
    mix *= np.minimum(np.clip(t / 0.08, 0, 1), np.clip((duration_s - t) / 1.8, 0, 1))[:, None]
    mix *= min(1.0, 0.85 / max(np.max(np.abs(mix)), 1e-6))

    premix = v.RT / 'v6-full-premix.wav'
    v.writewav(premix, mix)
    m = v.loudness(premix)
    gain_db = min(-20.5 - m['input_i'], -1.7 - m['input_tp'])
    gain_lin = 10 ** (gain_db / 20)
    mix_norm = np.clip(mix * gain_lin, -0.99, 0.99)

    out_full = HERE / '全片配乐_完整_v6.wav'
    subprocess.run([FF, '-y', '-v', 'error', '-i', str(premix), '-af', f'volume={gain_db}dB', '-c:a', 'pcm_s24le', str(out_full)], check=True)
    subprocess.run([FF, '-y', '-v', 'error', '-i', str(out_full), '-c:a', 'libmp3lame', '-b:a', '224k', str(out_full.with_suffix('.mp3'))], check=True)

    SEC = dict(sections)
    t_b1 = op_beats * BEAT_S
    t_b2 = SEC['city_enter'] * BEAT_S
    t_a3 = act_offsets['第三段'][0] * BEAT_S
    t_a4 = act_offsets['第四段'][0] * BEAT_S
    t_a5 = act_offsets['第五段'][0] * BEAT_S
    slices = [
        ('01_开场_主题A_v6.wav', 0.0, t_b1),
        ('02_第二段前半_进镜潮前_主题B1_v6.wav', t_b1, t_b2),
        ('03_第二段后半_进镜潮后_主题B2_v6.wav', t_b2, t_a3),
        ('04_第三段_回顾_主题Ap_v6.wav', t_a3, t_a4),
        ('05_第四段_展望_主题C_v6.wav', t_a4, t_a5),
        ('06_第五段_再见_主题C收束_v6.wav', t_a5, duration_s),
    ]
    for fname, s0, s1 in slices:
        seg = mix_norm[int(s0 * SR): int(s1 * SR)].copy()
        fade = int(0.05 * SR)
        if s0 > 0:
            seg[:fade] *= np.linspace(0, 1, fade, dtype=np.float32)[:, None]
        seg[-fade:] *= np.linspace(1, 0, fade, dtype=np.float32)[:, None]
        tmp_seg = v.RT / f'seg-{fname}'
        v.writewav(tmp_seg, seg)
        subprocess.run([FF, '-y', '-v', 'error', '-i', str(tmp_seg), '-c:a', 'pcm_s24le', str(HERE / fname)], check=True)

    write_full_sampler(mix_norm, sections)

    report = {
        'bpm': BPM,
        'duration_s': round(duration_s, 3),
        'total_beats': total_beats,
        'acts': {k: {'offset_beats': off, 'beats': ln, 'start_s': round(off * BEAT_S, 3)} for k, (off, ln) in act_offsets.items()},
        'sections': [{'name': k, 'beat': b, 'time_s': round(b * BEAT_S, 3)} for k, b in sections],
        'loudness': v.loudness(out_full),
        'compression_max_db': gr,
        'note_counts': {k: len(x) for k, x in NOTES.items()},
    }
    (HERE / 'render_report_full.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf8')
    print('Done! Full 5-act loudness:', report['loudness'], 'Notes:', report['note_counts'])


if __name__ == '__main__':
    main()
