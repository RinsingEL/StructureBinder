import copy,json,unittest
import workbench as w
from space_composer import compose,examples
from studio.navigation import audit

class SpaceComposer(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.assets=w.catalog();cls.recipes=examples();cls.registry=w.read(w.TOOL/'.cache/registry.json')
 def build(self,p):return compose(p,self.assets,w.rotate_state)
 def test_three_recipes_real_voxels_and_walkable_entries(self):
  for e in self.recipes:
   with self.subTest(e['id']):
    r=self.build(e['plan']);self.assertTrue(r['placements']);self.assertTrue(all(p['nbtSha256'] for p in r['placements']))
    points=[dict(id=str(i),kind='entrance' if i==0 else 'circulation',pos=[p['x'],2,p['z']]) for i,p in enumerate(e['plan']['entries'])]
    report=audit(r['world'],{'points':points},self.registry);self.assertTrue(report['passed'],report)
    occupied={(x,z) for p in r['placements'] for x in range(p['x'],p['x']+p['width']) for z in range(p['z'],p['z']+p['depth'])}
    self.assertTrue(all((b['pos'][0],b['pos'][2]) not in occupied for b in r['world']['blocks'] if b['layer']=='path'))
    w.write(w.ROOT/'runtime/space-composer'/f"{e['id']}-walk.json",report)
 def test_seeded_pool_is_repeatable(self):
  p=copy.deepcopy(self.recipes[1]['plan']);a=self.build(p);self.assertEqual(a,self.build(p));p['seed']+=1
  self.assertNotEqual(a['placements'],self.build(p)['placements'])
 def test_invalid_overlap_rejected(self):
  p=copy.deepcopy(self.recipes[0]['plan']);p['nodes'].append(dict(id='collision',x=48,z=40,assetId='studio:mg-42-v01'))
  with self.assertRaisesRegex(ValueError,'碰撞'):self.build(p)
 def test_reserved_open_space_rejected(self):
  p=copy.deepcopy(self.recipes[0]['plan']);p['clearings']=[dict(x=42,z=34,width=10,depth=10)]
  with self.assertRaisesRegex(ValueError,'留白'):self.build(p)
 def test_blocked_entry_rejected(self):
  p=copy.deepcopy(self.recipes[0]['plan']);p['entries'][0]={'x':48,'z':40}
  with self.assertRaisesRegex(ValueError,'入口'):self.build(p)
 def test_nested_translation(self):
  p=copy.deepcopy(self.recipes[1]['plan']);p.pop('plantingZones',None);p['nodes']=p['nodes'][:1];a=self.build(p);p['nodes'][0]['x']+=3;b=self.build(p)
  for x,y in zip(a['placements'],b['placements']):self.assertEqual(y['x'],x['x']+3);self.assertEqual(y['z'],x['z'])
 def test_planting_keeps_paths_and_clearings_open(self):
  p=copy.deepcopy(self.recipes[1]['plan']);rich=self.build(p);p['plantingDensity']=0;plain=self.build(p)
  self.assertEqual(rich['routes'],plain['routes']);self.assertGreater(rich['summary']['plants'],80);self.assertEqual(plain['summary']['plants'],0)
  reserved={(x,z) for c in p['clearings'] for x in range(c['x'],c['x']+c['width']) for z in range(c['z'],c['z']+c['depth'])}
  for a in rich['planting']['placements']:
   self.assertFalse({(x,z) for x in range(a['x'],a['x']+a['width']) for z in range(a['z'],a['z']+a['depth'])}&reserved)

if __name__=='__main__':unittest.main()
