from pathlib import Path
p=Path('tools/city_experiments/test_intentional_layout.py');s=p.read_text(encoding='utf-8');pos=s.index("if __name__=='__main__'");s=s[:pos]+'''class DesignedLandscapeIntegration(unittest.TestCase):
 def test_actual_voxels_and_greenery_toggle_preserve_circulation(self):
  old_output=api.OUTPUT
  with tempfile.TemporaryDirectory() as t:
   api.OUTPUT=Path(t)
   try:
    meta=api.build(api.read(Path(__file__).with_name('campus-groups.json')));folder=Path(meta['evidenceDirectory']);r=api.read(folder/'java-result.json');world=api.read(folder/'world.json')
    self.assertEqual(len(meta['members']),23);self.assertEqual(len(meta['terraces']),4)
    self.assertTrue(all(o['status'].startswith('CONNECTED') for o in r['streetReview']['accessOutcomes']))
    self.assertEqual(len(meta['fixtureChecks']),len(meta['fixtures']))
    self.assertTrue(all(c['supported'] and c['buildingClearance'] and c['roadClearance'] for c in meta['fixtureChecks']))
    plans=meta['publicSpacePlans'];self.assertEqual(len(plans),3)
    for p in plans[:2]:
     self.assertEqual(p['supportedColumns'],p['bounds']['width']*p['bounds']['depth']);self.assertEqual(len(p['beds']),4)
     self.assertGreater(p['counts'].get('FLOWER',0),0);self.assertGreater(len(p['trees']),0)
     cx=p['bounds']['x']+p['bounds']['width']//2;cz=p['bounds']['z']+p['bounds']['depth']//2
     for x,y,z,kind in p['writes']:
      if kind in ('LOW_HEDGE','FLOWER','TREE_TRUNK','TREE_CROWN'):
       self.assertGreater(abs(x-cx),2);self.assertGreater(abs(z-cz),2)
    self.assertTrue(any(a['kind']=='WELL' for a in plans[1]['amenities']))
    registry=api.read(api.TOOL/'.cache/registry.json')
    from studio.navigation import collision_boxes
    for palette in world['palette']:collision_boxes(palette,registry)
    before={p['nodeId']:{k:v for k,v in p['counts'].items() if k not in ('BED','LOW_HEDGE','FLOWER','TREE_TRUNK','TREE_CROWN')} for p in plans[:2]}
    r['scene']['greenery']=False;r['_groundIndex']={(row[0],row[1]):row[2] for row in r['terrainColumns']};api.compose(r,api.catalog())
    after={p['nodeId']:p['counts'] for p in r['publicSpacePlans']};self.assertEqual(before,after)
    self.assertEqual(api.read(api.OUTPUT/'state.json')['revision'],1)
   finally:api.OUTPUT=old_output

''' +s[pos:];p.write_text(s,encoding='utf-8')
