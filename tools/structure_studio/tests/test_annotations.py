"""Semantic regressions: broaden context without erasing physical constraints."""
import json
from pathlib import Path
import tempfile
import unittest

from studio.annotation_policy import refine_metadata
from studio.model import Model, sha256
from studio.refine_annotations import check_changes, differences, migrate


ROOT = Path(__file__).resolve().parents[3] / 'asset_catalogs/original_civilizations'


class AnnotationTests(unittest.TestCase):
    def read_asset(self, key):
        return json.loads(next(ROOT.glob(f'*/models/{key}/author.json')).read_text(encoding='utf-8'))

    def test_inland_nordic_keeps_height_and_real_shore_interfaces(self):
        for key in ['NS-08-v01', 'NS-01-v01', 'NS-11-v01', 'NS-12-v01']:
            old = self.read_asset(key)
            old['terrain']['选址'] = '稳定的寒地海湾背风岸台；避开潮涌、雪崩、海冰推挤和行洪通道。'
            new = refine_metadata(old)
            self.assertNotIn('寒地海湾背风岸台', new['terrain']['选址'])
            for field in ['高程', '岸线', '水深', '选用']:
                self.assertEqual(old['terrain'].get(field), new['terrain'].get(field))

    def test_ordinary_dorm_does_not_require_airport(self):
        old = self.read_asset('CN-06-v01')
        old['design_notes'].append('宿舍需与泊塔、修造库及商铺形成连续步行网络；床位标记不表示已接入住民系统。')
        new = refine_metadata(old)
        self.assertNotIn('宿舍需与泊塔', ''.join(new['design_notes']))
        self.assertEqual(old['terrain']['固定与承重'], new['terrain']['固定与承重'])
        self.assertEqual(old['connections'], new['connections'])

    def test_stays_and_supply_are_real_requirements(self):
        old = self.read_asset('SC-F02-v01')
        old['rooms'][0]['name'] = '短期营地种植畦'
        new = refine_metadata(old)
        self.assertEqual('长驻营地种植畦', new['rooms'][0]['name'])
        self.assertEqual(old['terrain']['选址'], new['terrain']['选址'])
        for key in ['DS-03-v01', 'TC-02-v01', 'RS-02']:
            original = self.read_asset(key)
            self.assertEqual(original['terrain'], refine_metadata(original)['terrain'])

    def test_arcane_ordinary_rooms_and_dedicated_plant_differ(self):
        dorm = refine_metadata(self.read_asset('AA-06-v01'))
        power = refine_metadata(self.read_asset('AA-03-v01'))
        self.assertNotIn('专用设备条件', dorm['terrain'])
        self.assertIn('静态表达', power['terrain']['专用设备条件'])
        self.assertIn('魔力配给场所', power['function_terms'])

    def test_all_assets_are_covered_idempotent_and_nonspatial(self):
        for path in ROOT.glob('*/models/*/author.json'):
            with self.subTest(asset=path.parent.name):
                old = json.loads(path.read_text(encoding='utf-8'))
                new = refine_metadata(old)
                self.assertTrue(new.get('function_terms'))
                self.assertEqual(new, refine_metadata(new))
                check_changes(old, list(differences(old, new)))
                for field in ['size', 'points', 'connections', 'floors', 'roof_min_y', 'preview_context', 'nbt_sha256']:
                    self.assertEqual(old.get(field), new.get(field))
                for a, b in zip(old['rooms'], new['rooms']):
                    self.assertEqual(a['min'], b['min'])
                    self.assertEqual(a['max'], b['max'])

    def test_unknown_asset_is_not_inferred_from_name(self):
        old = dict(id='NEW-v01', name='精灵住宅', terrain={'选址': '真实约束'})
        self.assertEqual(old, refine_metadata(old))

    def test_shop_industries_are_specific_and_author_overrides_survive(self):
        for key, term in [('AA-F01-v01', '烘焙'), ('NS-F01-v03', '钓具维修'), ('DS-F01-v06', '陶器制作')]:
            old = self.read_asset(key)
            old['function_terms'] = ['零售']
            self.assertIn(term, refine_metadata(old)['function_terms'])
            old['function_terms'] = ['作者另行确认的用途']
            self.assertEqual(old['function_terms'], refine_metadata(old)['function_terms'])

    def test_migration_preserves_nbt_and_old_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dest = root / 'sample/models/NS-08-v01'
            m = Model('NS-08-v01', 'fixture', (2, 2, 2))
            m.set(0, 0, 0, 'stone')
            m.export(dest)
            meta = json.loads((dest / 'author.json').read_text(encoding='utf-8'))
            del meta['function_terms']
            (dest / 'author.json').write_text(json.dumps(meta), encoding='utf-8')
            (dest / 'review.json').write_text('{"status":"accepted","author_sha256":"old"}', encoding='utf-8')
            before = {name: sha256(dest / name) for name in ['structure.nbt', 'author.json', 'review.json']}
            self.assertEqual(1, migrate(root)['changed'])
            self.assertEqual(before['author.json'], sha256(dest / 'author.json'))
            self.assertEqual(1, migrate(root, write=True)['changed'])
            self.assertEqual(0, migrate(root, write=True)['changed'])
            for name in ['structure.nbt', 'review.json']:
                self.assertEqual(before[name], sha256(dest / name))

    def test_reject_spatial_changes(self):
        with self.assertRaises(ValueError):
            check_changes({'id': 'SC-F02-v01'}, [{'path': ['rooms', 0, 'min', 0], 'before': 1, 'after': 2}])


if __name__ == '__main__':
    unittest.main()
