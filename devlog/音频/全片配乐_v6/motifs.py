"""Character motifs, chord-aware voice leading, and story-moment gestures for v6."""

MAJOR_SCALE = [0, 2, 4, 5, 7, 9, 11]


def degree_to_midi(tonic_midi: int, deg: int) -> int:
    """Map diatonic scale degree (0 = tonic, can be negative or >=7) to MIDI pitch."""
    oct_shift, step = divmod(deg, 7)
    return tonic_midi + oct_shift * 12 + MAJOR_SCALE[step]


BORROWED_PC_REMAP = {
    'Gm6':   {11: 10, 6: 7},        # B -> Bb, F# -> G
    'Cm6':   {4: 3, 11: 10, 6: 7},  # E -> Eb, B -> Bb, F# -> G
    'Am7b5': {4: 3, 11: 0, 6: 7},   # E -> Eb, B -> C, F# -> G
}


def snap_to_harmony(midi_pitch: int, chord_tones: list[int], tonic_pc: int, chord_sym: str = '') -> int:
    """Adapt diatonic melody notes ONLY for non-diatonic borrowed chords (avoiding false snaps on roots/7ths)."""
    remap = BORROWED_PC_REMAP.get(chord_sym)
    if not remap:
        return midi_pitch
    pc = midi_pitch % 12
    if pc in remap:
        target_pc = remap[pc]
        diff = (target_pc - pc + 6) % 12 - 6
        return midi_pitch + diff
    return midi_pitch


# Each entry: list of (beat_offset, scale_degree_or_list_of_degrees, duration_beats, velocity)
# Base registers:
#   rin_piano: tonic D4 (62) / G4 (67)
#   gemini_flute: tonic D5 (74) / G5 (79)
#   claude_clarinet: tonic D4 (62) / G4 (67)
#   astra_epiano: tonic D4 (62) / G4 (67)
#   deepseek_pizz: tonic D4 (62) / G4 (67)
#   glm_vibes: tonic D5 (74) / G4 (67)
BASE_TONIC_OFFSET = {
    'rin_piano': 0,
    'gemini_flute': 0,
    'claude_clarinet': 0,
    'astra_epiano': 0,
    'deepseek_pizz': 0,
    'glm_vibes': 0,
}

# Primary motifs: unmistakable character signatures that still share the club's warm 5th/6th family interval.
PRIMARY_MOTIFS = {
    'rin_piano': [
        (0.0, 2, 1.3, 56),
        (1.0, 4, 1.3, 58),
        (2.0, 7, 1.9, 61),
        (4.0, 5, 1.1, 55),
        (5.0, 4, 2.0, 50),
    ],
    'gemini_flute': [
        (0.0, 4, 0.9, 54),
        (1.0, 6, 0.9, 56),
        (2.0, 7, 1.5, 58),
        (3.5, 8, 0.9, 58),
        (4.5, 7, 1.0, 54),
        (5.5, 6, 1.5, 50),
    ],
    'claude_clarinet': [
        (0.0, 2, 0.95, 62),
        (1.0, 3, 0.95, 65),
        (2.0, 4, 1.4, 70),
        (3.5, 5, 0.9, 64),
        (4.5, 4, 0.9, 60),
        (5.5, 2, 1.4, 54),
    ],
    'astra_epiano': [
        (0.0, [2, 4], 0.9, 64),
        (1.0, [4, 6], 0.9, 66),
        (2.0, [5, 7], 1.4, 70),
        (3.5, [4, 6], 1.1, 60),
        (5.0, [2, 4], 1.3, 54),
    ],
    'deepseek_pizz': [
        (0.0, 7, 0.35, 68),
        (0.5, 4, 0.35, 62),
        (1.0, 2, 0.35, 64),
        (1.5, 5, 0.5, 70),
        (2.5, 4, 0.4, 62),
        (3.5, 1, 0.4, 56),
        (4.0, 0, 0.6, 58),
    ],
    'glm_vibes': [
        (0.0, 1, 1.2, 60),
        (1.0, 4, 1.4, 64),
        (2.0, 5, 1.6, 68),
        (3.5, 7, 1.8, 66),
        (5.5, 9, 1.8, 56),
    ],
}

# Conversational fills for longer explanations (cycled so long speeches never loop identically).
CONVERSATION_VARIATIONS = {
    'rin_piano': [
        [(0.0, 4, 1.3, 52), (1.0, 5, 1.2, 54), (2.0, 4, 1.8, 52), (4.0, 2, 1.8, 48)],
        [(0.0, 2, 1.2, 50), (1.0, 4, 1.4, 54), (3.0, 7, 2.0, 55)],
    ],
    'gemini_flute': [
        [(0.0, 7, 0.9, 52), (1.0, 6, 0.9, 50), (2.0, 4, 1.4, 54), (4.0, 5, 1.4, 48)],
        [(0.0, 4, 0.9, 50), (1.0, 7, 1.4, 54), (3.0, 5, 1.0, 50), (4.0, 4, 1.5, 48)],
    ],
    'claude_clarinet': [
        [(0.0, 4, 0.95, 58), (1.0, 2, 0.95, 56), (2.0, 1, 1.2, 52), (3.5, 2, 1.4, 54)],
        [(0.0, 1, 0.9, 56), (1.0, 2, 0.9, 58), (2.0, 4, 1.4, 62), (4.0, 2, 1.2, 52)],
        [(0.0, 5, 0.9, 58), (1.0, 4, 0.9, 56), (2.0, 2, 1.3, 54), (3.5, 0, 1.4, 50)],
    ],
    'astra_epiano': [
        [(0.0, 4, 0.8, 58), (1.0, 5, 0.8, 60), (2.0, [2, 7], 1.4, 62), (4.0, 4, 1.2, 52)],
        [(0.0, [1, 4], 1.0, 56), (1.5, [2, 5], 1.1, 60), (3.0, [4, 6], 1.5, 56)],
    ],
    'deepseek_pizz': [
        [(0.0, 4, 0.35, 58), (0.5, 2, 0.35, 54), (1.5, 4, 0.45, 60), (2.5, 0, 0.5, 52)],
        [(0.0, 2, 0.3, 56), (0.5, 4, 0.35, 58), (1.5, 7, 0.4, 60), (2.5, 4, 0.4, 52)],
    ],
    'glm_vibes': [
        [(0.0, 5, 1.3, 56), (1.5, 4, 1.3, 54), (3.0, 2, 1.6, 52)],
        [(0.0, 4, 1.2, 54), (1.5, 7, 1.5, 58), (3.5, 5, 1.5, 50)],
    ],
}

# Quiet companion responses when the previous speaker listens and nods along.
SUPPORT_ECHOES = {
    'rin_piano': [(0.0, 4, 1.2, 46), (1.5, 2, 1.4, 44)],
    'gemini_flute': [(0.0, 7, 0.9, 46), (1.5, 9, 1.3, 44)],
    'claude_clarinet': [(0.0, 2, 1.1, 46), (1.5, 4, 1.4, 44)],
    'astra_epiano': [(0.0, [2, 4], 1.4, 46), (2.0, [4, 7], 1.5, 44)],
    'deepseek_pizz': [(0.0, 4, 0.35, 48), (1.0, 7, 0.45, 46)],
    'glm_vibes': [(0.0, 7, 1.6, 46), (2.0, 4, 1.8, 42)],
}

# Theme B2: Calm, sunlit Mirror Tide Campus melody (G major around D4..D5 / deg -3..4)
# Shares common tones D/B/A with Theme B1 so entering at 6:29 feels organic, not abrupt.
CAMPUS_THEME_B2 = [
    (0.0, 4, 1.9, 52), (2.0, 2, 0.95, 50), (3.0, 1, 0.95, 48),
    (4.0, 1, 1.8, 50), (6.0, 0, 0.95, 48), (7.0, -1, 0.95, 46),
    (8.0, 0, 1.9, 50), (10.0, 2, 0.95, 52), (11.0, 4, 0.95, 54),
    (12.0, 2, 3.2, 50),
    (16.0, 5, 1.4, 52), (17.5, 4, 0.6, 50), (18.0, 3, 1.4, 52), (19.5, 2, 0.6, 48),
    (20.0, 4, 1.8, 52), (22.0, 2, 1.8, 48),
    (24.0, 3, 1.4, 50), (25.5, 2, 0.9, 48), (26.5, 1, 1.4, 46),
    (28.0, 1, 3.0, 44),
]

# Natural, conversational G-major fills in each instrument's comfortable mid-register (no high 4th jump)
B2_CONVERSATIONS = {
    'rin_piano': [
        [(0.0, 4, 1.4, 50), (2.0, 2, 1.2, 48), (3.5, 1, 1.4, 46)],
        [(0.0, 0, 1.3, 48), (1.5, 2, 1.2, 50), (3.0, 4, 1.8, 52)],
    ],
    'gemini_flute': [
        [(0.0, 4, 1.1, 48), (1.0, 2, 1.0, 46), (2.0, 0, 1.5, 50), (4.0, 1, 1.4, 46)],
        [(0.0, 2, 1.0, 48), (1.5, 4, 1.2, 50), (3.0, 5, 1.4, 50), (4.5, 4, 1.2, 46)],
    ],
    'claude_clarinet': [
        [(0.0, -3, 1.1, 52), (1.0, -1, 1.1, 54), (2.0, 0, 1.6, 56), (4.0, 2, 1.4, 50)],
        [(0.0, 2, 1.1, 54), (1.0, 1, 1.0, 52), (2.0, 0, 1.5, 50), (4.0, -3, 1.5, 48)],
        [(0.0, 0, 1.0, 52), (1.0, 2, 1.0, 54), (2.0, 4, 1.5, 56), (4.0, 2, 1.4, 50)],
    ],
    'astra_epiano': [
        [(0.0, [-1, 2], 1.2, 54), (1.5, [0, 4], 1.3, 56), (3.5, [-1, 2], 1.4, 50)],
        [(0.0, [-3, 0], 1.1, 52), (1.5, [-1, 2], 1.2, 54), (3.5, [0, 4], 1.5, 52)],
    ],
    'deepseek_pizz': [
        [(0.0, 4, 0.35, 54), (0.5, 2, 0.35, 50), (1.5, 0, 0.45, 52), (2.5, -3, 0.5, 48)],
        [(0.0, 0, 0.35, 52), (0.5, 2, 0.35, 54), (1.5, 4, 0.45, 56), (2.5, 2, 0.45, 48)],
    ],
    'glm_vibes': [
        [(0.0, 4, 1.4, 52), (1.5, 2, 1.3, 50), (3.0, 0, 1.6, 48)],
        [(0.0, 1, 1.3, 50), (1.5, 4, 1.5, 52), (3.5, 2, 1.5, 48)],
    ],
}

B2_SUPPORT_ECHOES = {
    'rin_piano': [(0.0, 2, 1.3, 44), (1.5, 0, 1.4, 42)],
    'gemini_flute': [(0.0, 2, 1.0, 44), (1.5, 4, 1.3, 42)],
    'claude_clarinet': [(0.0, -3, 1.2, 44), (1.5, 0, 1.4, 42)],
    'astra_epiano': [(0.0, [-1, 2], 1.4, 44), (2.0, [0, 4], 1.5, 42)],
    'deepseek_pizz': [(0.0, 0, 0.35, 46), (1.0, 4, 0.45, 44)],
    'glm_vibes': [(0.0, 4, 1.6, 44), (2.0, 2, 1.8, 40)],
}

# Heartfelt lyrical duet for Rin (Piano) + Gemini (Flute) at the Observatory photo (2-60..2-64)
# In key of G major (tonic 67 for piano, 79 for flute), relative to 2-60 start beat.
OBSERVATORY_PIANO = [
    (0.0, 0, 1.8, 60), (2.0, 2, 1.0, 62), (3.0, 4, 1.0, 65),
    (4.0, 7, 2.2, 68), (6.5, 6, 1.3, 62),
    (8.0, 4, 1.8, 64), (10.0, 5, 1.0, 62), (11.0, 4, 1.0, 60),
    (12.0, 2, 2.6, 58), (15.0, 1, 1.0, 54),
    (16.0, 0, 1.8, 62), (18.0, 4, 1.0, 64), (19.0, 5, 1.0, 66),
    (20.0, 7, 2.4, 70), (22.5, 9, 1.4, 68),
    (24.0, 8, 1.8, 65), (26.0, 7, 1.0, 62), (27.0, 5, 1.0, 60),
    (28.0, 4, 3.2, 62),
]

OBSERVATORY_FLUTE = [
    (8.0, 7, 1.5, 58), (9.5, 6, 0.8, 56), (10.5, 4, 1.5, 60),
    (12.0, 5, 2.2, 62), (14.5, 4, 1.4, 56),
    (16.0, 2, 1.8, 58), (18.0, 4, 1.8, 62),
    (20.0, 7, 2.0, 66), (22.5, 9, 1.5, 68),
    (24.0, 8, 1.5, 64), (25.5, 7, 1.0, 62), (26.5, 5, 1.2, 60),
    (28.0, 7, 3.5, 64),
]
