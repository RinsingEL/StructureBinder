import json
from pathlib import Path
import tempfile
import unittest
from studio.academy_support import BUILDERS
from studio.server import TOOL
from studio.validate import validate


class AcademySupportAccessTests(unittest.TestCase):
    def test_dorm_exports_sixteen_physical_resident_beds(self):
        registry=json.loads((TOOL/'.cache/registry.json').read_text(encoding='utf-8'))
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);BUILDERS['MG-27']().export(root,registry)
            facilities=json.loads((root/'living_facilities.json').read_text(encoding='utf-8'))
            self.assertEqual(16,facilities['capacity']['residentBeds'])
            self.assertEqual(16,sum(f['kind']=='bed' for f in facilities['facilities']))
    def test_shared_house_variants_keep_the_indoor_access_target_reachable(self):
        registry = json.loads((TOOL / '.cache/registry.json').read_text(encoding='utf-8'))
        for key in ('MG-25', 'MG-26', 'MG-27', 'MG-28', 'MG-32'):
            with self.subTest(template=key), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                BUILDERS[key]().export(root, registry)
                report = validate(root, registry)
                self.assertTrue(report['navigation']['passed'], report['navigation'])

    def test_small_dorm_entrance_reaches_the_room_after_window_framing(self):
        model = BUILDERS['MG-27']()
        registry = json.loads((TOOL / '.cache/registry.json').read_text(encoding='utf-8'))
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            model.export(root, registry)
            report = validate(root, registry)
            self.assertTrue(report['navigation']['passed'], report['navigation'])
            model.set(9, 3, 4, 'polished_andesite')
            model.set(8, 3, 4, 'purple_stained_glass')
            model.set(10, 3, 4, 'purple_stained_glass')
            model.export(root, registry)
            blocked = validate(root, registry)
            self.assertFalse(blocked['navigation']['passed'], blocked['navigation'])
            self.assertIn('main_access', blocked['navigation']['unreachable'])
