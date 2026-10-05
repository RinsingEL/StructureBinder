"""Multi-storey rooms must be reachable from the street, not extra path seeds."""
import json
from pathlib import Path
import tempfile
import unittest

from studio.chinese_inn import tall_inn
from studio.server import TOOL
from studio.validate import validate


class TallInnTests(unittest.TestCase):
    def test_guest_capacity_and_stairs_from_ground_level_entrances(self):
        m = tall_inn()
        rooms = [r for r in m.meta['rooms'] if r['id'].startswith('guest_')]
        self.assertEqual(len(rooms), 12)
        beds = {p for p, (name, props) in m.blocks.items()
                if name.endswith('_bed') and dict(props)['part'] == 'foot'}
        for room in rooms:
            self.assertEqual(sum(all(room['min'][i] <= p[i] <= room['max'][i]
                                     for i in range(3)) for p in beds), 2, room['id'])
        self.assertEqual(len(beds), 26)  # 24 guest beds and 2 staff beds.
        entrances = [p for p in m.meta['points'] if p['kind'] == 'entrance']
        self.assertEqual({p['id'] for p in entrances}, {'front', 'delivery'})
        self.assertTrue(all(p['pos'][1] == 2 for p in entrances))
        registry = json.loads((TOOL / '.cache/registry.json').read_text())
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            m.export(path, registry)
            report = validate(path, registry)
            self.assertTrue(report['passed'], report['errors'])
            self.assertTrue(report['navigation']['passed'], report['navigation'])
            # A disconnected upper floor must fail instead of starting a fresh
            # flood fill at each room. Remove the first flight as a negative control.
            m.box((23,3,8), (25,9,14), 'air')
            m.export(path, registry)
            broken = validate(path, registry)
            self.assertFalse(broken['navigation']['passed'])
            self.assertTrue(broken['navigation']['unreachable'])


if __name__ == '__main__':
    unittest.main()
