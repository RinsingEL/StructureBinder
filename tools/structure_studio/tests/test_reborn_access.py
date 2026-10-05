"""Elevated rooms must be reached via built stairs, not independent entry seeds."""
import json
from pathlib import Path
import tempfile
import unittest
from studio.elven_reborn import terrace_inn
from studio.dwarven_reborn import mining_guild
from studio.server import TOOL
from studio.validate import validate

class RebornAccessTests(unittest.TestCase):
    def test_elven_guest_bridge_requires_rear_stair(self):
        model=terrace_inn()
        registry=json.loads((TOOL/'.cache/registry.json').read_text())
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder);model.export(path,registry)
            good=validate(path,registry)
            self.assertTrue(good['navigation']['passed'],good['navigation'])
            model.box((27,2,33),(31,6,37),'air')
            model.export(path,registry)
            bad=validate(path,registry)
            self.assertFalse(bad['navigation']['passed'])
            self.assertIn('bridge',bad['navigation']['unreachable'])

    def test_guild_workstation_is_a_real_navigation_target(self):
        model=mining_guild()
        registry=json.loads((TOOL/'.cache/registry.json').read_text())
        point=next(p for p in model.meta['points'] if p['id']=='dispatch')
        self.assertIn('approach',point)
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder);model.export(path,registry)
            self.assertTrue(validate(path,registry)['navigation']['passed'])
            model.box((26,3,24),(32,6,26),'deepslate_bricks')
            model.export(path,registry)
            bad=validate(path,registry)
            self.assertIn('dispatch',bad['navigation']['unreachable'])

if __name__=='__main__':unittest.main()

class EuropeanAccessTests(unittest.TestCase):
    def test_three_storey_inn_keeps_ground_floor_and_requires_both_flights(self):
        from studio.european_reborn import inn
        model=inn()
        registry=json.loads((TOOL/'.cache/registry.json').read_text())
        beds=[p for p,(name,props) in model.blocks.items() if name.endswith('_bed') and dict(props).get('part')=='foot']
        self.assertEqual(len(beds),8)
        self.assertEqual({p[1] for p in beds},{9,16})
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder);model.export(path,registry)
            good=validate(path,registry)
            self.assertTrue(good['navigation']['passed'],good['navigation'])
            model.box((12,9,19),(14,15,25),'air')
            model.export(path,registry)
            bad=validate(path,registry)
            self.assertIn('third',bad['navigation']['unreachable'])
            self.assertNotIn('second',bad['navigation']['unreachable'])
            self.assertNotIn('reception',bad['navigation']['unreachable'])
