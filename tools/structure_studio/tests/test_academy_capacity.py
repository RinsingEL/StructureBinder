"""Final-NBT capacity, access and unchanged-envelope checks for the academy v02s."""
from collections import Counter
import json
from pathlib import Path
import tempfile
import unittest

from studio.academy_capacity import BUILDERS
from studio.academy_classrooms import BUILDERS as CLASSROOMS
from studio.academy_interiors import BUILDERS as INTERIORS
from studio.arcane_revision2 import dormitory, infirmary, professor_home
from studio.living import extract
from studio.lighting import audit as lighting_audit
from studio.model import read_structure
from studio.server import TOOL
from studio.validate import validate


CASES = {
    'MG-05-v02': (dormitory, 60, 'resident', [
        ((x0 + 1, y, 18), (x0 + 15, y + 4, 54))
        for x0 in (9, 43) for y in (2, 9, 16)]
        + [((x0, y + 3, 20), (x0 + 16, y + 5, 20)) for x0 in (9, 43) for y in (2, 9, 16)]
        + [((29, 1, 22), (39, 1, 42))]),
    'MG-15-v02': (infirmary, 12, 'care', [
        ((x0 + 1, 2, 33), (x0 + 15, 6, 48)) for x0 in (6, 42)]
        + [((24, 2, 18), (40, 5, 57))]),
    'MG-17-v02': (professor_home, 6, 'resident', [((15, 10, 18), (27, 13, 40)),
        ((9, 2, 19), (27, 8, 40)), ((9, 10, 19), (27, 13, 39)), ((38, 2, 26), (38, 5, 39))]),
}


class AcademyCapacityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.folder = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.folder.cleanup)
        registry = json.loads((TOOL / '.cache/registry.json').read_text(encoding='utf-8'))
        cls.registry = registry
        cls.exports = {}
        for key in (BUILDERS | CLASSROOMS):
            builder = INTERIORS[key]
            model = builder()
            path = Path(cls.folder.name) / key
            model.export(path, registry)
            data = read_structure(path / 'structure.nbt')
            author = json.loads((path / 'author.json').read_text(encoding='utf-8'))
            cls.exports[key] = (model, data, author, extract(data, author), validate(path, registry))

    def test_actual_complete_beds_have_correct_use_and_access(self):
        for key, (_, expected, use, _) in CASES.items():
            with self.subTest(asset=key):
                _, data, author, living, report = self.exports[key]
                heads = [b for b in data['blocks']
                         if data['palette'][b['state']]['name'].endswith('_bed')
                         and data['palette'][b['state']]['properties']['part'] == 'head']
                self.assertEqual(len(heads), expected)
                beds = [f for f in living['facilities'] if f['kind'] == 'bed']
                self.assertEqual(Counter(f['use'] for f in beds), {use: expected})
                self.assertEqual(living['capacity']['residentBeds'], expected if use == 'resident' else 0)
                points = [p for p in author['points'] if p['kind'] == 'bed']
                self.assertEqual(len(points), expected)
                self.assertTrue(all('approach' in p for p in points))
                self.assertTrue(report['passed'], report['errors'])
                self.assertTrue(report['navigation']['passed'], report['navigation'])
                self.assertEqual(report['warnings'], [])
                self.assertEqual(living['warnings'], [])

    def test_v01_envelopes_entrances_stairs_and_dimensions_are_preserved(self):
        for key, (base_builder, _, _, rooms) in CASES.items():
            with self.subTest(asset=key):
                original = base_builder()
                revised = BUILDERS[key]()
                self.assertEqual(original.size, revised.size)
                for field in ('ground_plane', 'floors', 'roof_min_y', 'function_terms'):
                    self.assertEqual(original.meta[field], revised.meta[field])
                self.assertEqual([p for p in original.meta['points'] if p['kind'] == 'entrance'],
                                 [p for p in revised.meta['points'] if p['kind'] == 'entrance'])
                changed = [pos for pos in original.blocks.keys() | revised.blocks.keys()
                           if original.blocks.get(pos) != revised.blocks.get(pos)]
                self.assertTrue(changed)
                outside = [pos for pos in changed if not any(
                    all(lo[i] <= pos[i] <= hi[i] for i in range(3)) for lo, hi in rooms)]
                self.assertEqual(outside, [], 'Interior revision changed the accepted exterior or stairs')

    def test_capacity_does_not_turn_bedside_storage_into_jobs(self):
        expected_workplaces = {'MG-05-v02': 0, 'MG-15-v02': 3, 'MG-17-v02': 1}
        for key, expected in expected_workplaces.items():
            with self.subTest(asset=key):
                living = self.exports[key][3]
                self.assertEqual(living['capacity']['workplaces'], expected)
                self.assertFalse(any(f['kind'] == 'work' and f['block'] == 'minecraft:barrel'
                                     for f in living['facilities']))

    def test_classrooms_have_usable_teacher_workplaces_and_separate_student_seats(self):
        for key, expected_seats, expected_teachers in (('MG-25-v02', 24, 2), ('MG-26-v02', 8, 1)):
            with self.subTest(asset=key):
                _, _, author, living, report = self.exports[key]
                self.assertEqual(living['capacity']['workplaces'], expected_teachers)
                self.assertEqual(sum(f['kind'] == 'seat' for f in living['facilities']), expected_seats)
                self.assertTrue(all('approach' in p for p in author['points'] if p['kind'] in ('seat', 'work')))
                self.assertTrue(report['navigation']['passed'], report['navigation'])
                self.assertEqual(report['warnings'], [])
                self.assertEqual(living['warnings'], [])

    def test_original_versions_keep_their_original_bed_counts(self):
        for builder, expected in ((dormitory, 24), (infirmary, 4), (professor_home, 2)):
            model = builder()
            heads = sum(name.endswith('_bed') and dict(props).get('part') == 'head'
                        for name, props in model.blocks.values())
            self.assertEqual(heads, expected)
            self.assertTrue(model.meta['id'].endswith('-v01'))

    def test_fixed_lighting_covers_authored_use_points_without_daylight(self):
        for key, (_, data, author, _, _) in self.exports.items():
            with self.subTest(asset=key):
                report = lighting_audit(data, author, self.registry)
                self.assertEqual(report['darkOccupiedPoints'], [])
                self.assertEqual(report['darkFloorSamples'], [])
                self.assertTrue(report['passed'])


if __name__ == '__main__':
    unittest.main()
