"""Street-to-entry regression coverage, including a submerged lower floor."""
import json
from pathlib import Path
import tempfile
import unittest

from backfill_studio_ground_planes import ROOT, builders, outside_access


class OutsideAccessTests(unittest.TestCase):
    def test_explicit_datum_aligns_a_stone_base_and_zero_does_not(self):
        registry = {
            'minecraft:stone': dict(default={},properties={},state_order=[],collisions=[[[0,0,0,1,1,1]]]),
            'minecraft:air': dict(default={},properties={},state_order=[],collisions=[[]]),
        }
        data = dict(size=[5,7,5],palette=[dict(name=name,properties={}) for name in registry],
                    blocks=[dict(pos=[x,y,z],state=0 if y<3 else 1)
                            for x in range(5) for z in range(2,5) for y in range(7)])
        point=dict(id='entry',pos=[2,3,3],facing='north')
        self.assertTrue(outside_access(data,point,3,registry)['passed'])
        self.assertFalse(outside_access(data,point,0,registry)['passed'])
        # NBT air must not be silently filled to manufacture a walkable approach.
        data['blocks'].append(dict(pos=[2,2,0],state=1))
        self.assertFalse(outside_access(data,point,3,registry)['passed'])

    def test_special_asset_datums_follow_the_actual_external_approach(self):
        registry=json.loads((ROOT/'tools/structure_studio/.cache/registry.json').read_text(encoding='utf-8'))
        registered=builders()
        for key in ('DS-11-v01','TC-08-v01','ML-11-v01'):
            with self.subTest(asset=key), tempfile.TemporaryDirectory() as temp:
                model=registered[key]()
                datum=model.meta['ground_plane']['y']
                point=next(p for p in model.meta['points'] if p['kind']=='entrance')
                data=model.export(Path(temp),registry)
                self.assertTrue(outside_access(data,point,datum,registry)['passed'])
                if key=='TC-08-v01':
                    self.assertFalse(outside_access(data,point,18,registry)['passed'])
                    self.assertFalse(outside_access(data,point,4,registry)['passed'])
                elif key=='ML-11-v01':
                    self.assertFalse(outside_access(data,point,3,registry)['passed'])
                    air=next(i for i,b in enumerate(data['palette']) if b['name']=='minecraft:air')
                    for block in data['blocks']:
                        x,y,z=block['pos']
                        if 18<=x<=20 and 3<=y<=8 and z==1:
                            block['state']=air
                    self.assertFalse(outside_access(data,point,datum,registry)['passed'])
                else:
                    # Restore the old missing upper stair layer: the 1-block lip fails.
                    data['blocks']=[b for b in data['blocks'] if tuple(b['pos']) not in {(17,1,3),(18,1,3),(19,1,3)}]
                    self.assertFalse(outside_access(data,point,datum,registry)['passed'])


if __name__=='__main__':
    unittest.main()
