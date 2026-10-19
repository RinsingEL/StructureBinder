"""Delivery checks for the entire requested academy scope, using the saved assets."""
import json
from pathlib import Path
import unittest

from studio.academy_interiors import NUMBERS
from studio.model import read_structure, sha256
from studio.living import extract
from studio.server import CATALOG


class AcademyInteriorDeliveryTests(unittest.TestCase):
    def test_all_enterable_families_have_current_validated_versions_and_explicit_jobs(self):
        self.assertEqual(len(NUMBERS), 36)
        root = CATALOG / 'R02_arcane_reborn/models'
        for number in NUMBERS:
            key = f'MG-{number:02d}-v{3 if number in (34, 37) else 2:02d}'
            old = root / f'MG-{number:02d}-v{2 if number in (34, 37) else 1:02d}'
            path = root / key
            with self.subTest(asset=key):
                author = json.loads((path / 'author.json').read_text(encoding='utf-8'))
                original = json.loads((old / 'author.json').read_text(encoding='utf-8'))
                validation = json.loads((path / 'validation.json').read_text(encoding='utf-8'))
                lighting = json.loads((path / 'lighting.json').read_text(encoding='utf-8'))
                current_hash = sha256(path / 'structure.nbt')
                self.assertEqual(author['size'], original['size'])
                self.assertEqual(author['workplace_policy'], 'authored')
                self.assertEqual(author['nbt_sha256'], current_hash)
                self.assertEqual(validation['nbt_sha256'], current_hash)
                self.assertTrue(validation['passed'])
                self.assertTrue(validation['navigation']['passed'])
                self.assertEqual(validation['warnings'], [])
                self.assertEqual(lighting['nbt_sha256'], current_hash)
                self.assertTrue(lighting['passed'], lighting['darkOccupiedPoints'])
                facilities = extract(read_structure(path / 'structure.nbt'), author)
                self.assertEqual(facilities['warnings'], [])
                self.assertEqual(facilities['capacity']['workplaces'],
                                 len({tuple(p['pos']) for p in author['points'] if p['kind'] == 'work'}))

    def test_small_refectory_has_real_chairs_and_staff_positions(self):
        path = CATALOG / 'R02_arcane_reborn/models/MG-28-v02'
        author = json.loads((path / 'author.json').read_text(encoding='utf-8'))
        facilities = extract(read_structure(path / 'structure.nbt'), author)
        seats = [f for f in facilities['facilities'] if f['kind'] == 'seat']
        self.assertEqual(len(seats), 6)
        self.assertTrue(all(f['block'] == 'minecraft:birch_stairs' and 'approach' in f for f in seats))
        self.assertEqual(facilities['capacity']['workplaces'], 2)


if __name__ == '__main__':
    unittest.main()
