"""The raised hall and throne must be connected to the exterior entrance."""
import json
from pathlib import Path
import tempfile
import unittest

from studio.chinese_palace import palace_grand
from studio.server import TOOL
from studio.validate import validate


class GrandPalaceTests(unittest.TestCase):
    def test_raised_hall_reached_only_through_real_terrace_stairs(self):
        m = palace_grand()
        entrances = [p for p in m.meta['points'] if p['kind'] == 'entrance']
        self.assertEqual([(p['id'], p['pos']) for p in entrances], [('front', [40,2,0])])
        registry = json.loads((TOOL / '.cache/registry.json').read_text())
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            m.export(path, registry)
            report = validate(path, registry)
            self.assertTrue(report['passed'], report['errors'])
            self.assertTrue(report['navigation']['passed'], report['navigation'])
            # Negative control: neither the main hall nor rear terrace may seed
            # a separate flood fill after both ground-to-terrace stairs are lost.
            m.box((36,2,30), (44,5,33), 'air')
            m.box((37,2,63), (43,5,66), 'air')
            m.export(path, registry)
            broken = validate(path, registry)
            self.assertFalse(broken['navigation']['passed'])
            self.assertTrue(broken['navigation']['unreachable'])


if __name__ == '__main__':
    unittest.main()
