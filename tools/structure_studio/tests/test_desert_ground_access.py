import copy
import json
from pathlib import Path
import tempfile
import unittest

from studio.desert_caravanserai import caravanserai
from studio.navigation import audit
from studio.server import TOOL


class DesertGroundAccessTest(unittest.TestCase):
    def test_caravanserai_reachable_from_authored_outside_ground(self):
        registry = json.loads((TOOL / '.cache/registry.json').read_text(encoding='utf-8'))
        model = caravanserai()
        ground = model.meta['ground_plane']['y']
        # Supply a small external street at the declared datum, outside the model's gate.
        model.box((24, ground-1, 0), (28, ground-1, 2), 'sandstone')
        meta = copy.deepcopy(model.meta)
        for point in meta['points']:
            if point['kind'] == 'entrance':
                point['kind'] = 'circulation'
        meta['points'].append(dict(id='street', kind='entrance', pos=[26, ground, 1]))
        with tempfile.TemporaryDirectory() as temp:
            data = model.export(Path(temp), registry)
        result = audit(data, meta, registry)
        self.assertTrue(result['passed'], result)
