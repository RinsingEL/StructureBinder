import sys,json,subprocess,tempfile,unittest,copy
from pathlib import Path
import workbench as api

class IntentionalLayouts(unittest.TestCase):
 def setUp(self):
  self.scene=api.read(Path(__file__).with_name('campus-groups.json'));self.assets=api.catalog()
 def java(self,scene):
  with tempfile.TemporaryDirectory() as t:
   p=Path(t);req={**scene,'assets':self.assets,'terrainPath':str(api.EXPERIMENT/'data/D3地形原始数据.json')};api.write(p/'in.json',req)
   subprocess.run(['C:/Program Files/Java/jdk-17/bin/java.exe','-cp',(api.ROOT/'build/city-workbench-classpath.txt').read_text(),'com.rinsing.geomantia.systems.city.application.CityArrayExperiment',str(p/'in.json'),str(p/'out.json')],check=True,capture_output=True)
   r=api.read(p/'out.json');self.assertTrue(r['ok'],r.get('error'));return r
 def test_nested_geometry_void_fronts_and_translation(self):
  a=self.java(self.scene);self.assertEqual(len(a['members']),23)
  self.assertTrue(all(not m['issues'] and not m['terrainEvidence']['waterAreaBlocks'] for m in a['members']))
  court=[m for m in a['members'] if m['arrayId']=='main-court'];self.assertEqual([m['rotation'] for m in court],[180,90,270,0])
  self.assertEqual(len(a['layoutSpaces']),2)
  for s in a['layoutSpaces']:
   for m in a['members']:
    p=m['originBlock'];self.assertFalse(p['x']<s['x']+s['width'] and p['x']+m['width']>s['x'] and p['z']<s['z']+s['depth'] and p['z']+m['depth']>s['z'])
  shifted=copy.deepcopy(self.scene);shifted['compositions'][-1]['anchorBlock']['x']+=7;shifted['compositions'][-1]['anchorBlock']['z']+=9
  b=self.java(shifted)
  for ma,mb in zip(a['members'],b['members']):self.assertEqual(mb['originBlock'],dict(x=ma['originBlock']['x']+7,z=ma['originBlock']['z']+9))
  for sa,sb in zip(a['layoutSpaces'],b['layoutSpaces']):self.assertEqual((sb['x']-sa['x'],sb['z']-sa['z']),(7,9))
 def test_other_asset_family_uses_same_court(self):
  scene=copy.deepcopy(self.scene);scene['arrays']=scene['arrays'][:1];scene['compositions']=[]
  a=scene['arrays'][0];a['coreAssetId']='studio:eu-01-v01';a['requiredAssetIds']=['studio:eu-02-v01','studio:eu-03-v01','studio:eu-04-v01'];a['anchorBlock']=dict(x=-2990,z=5300)
  r=self.java(scene);self.assertEqual(len(r['members']),4);self.assertTrue(all(not m['collisionsWith'] for m in r['members']));self.assertTrue(all(m['assetId'].startswith('studio:eu-') for m in r['members']))
 def test_complete_new_assets_and_schema(self):
  api.validate_scene(self.scene,self.assets)
  for ident in ('MG-43-v01','MG-44-v01','MG-45-v01','MG-34-v02','MG-37-v02'):
   a=next(a for a in self.assets if a['id']==ident);meta=api.read(Path(a['path'])/'author.json');validation=api.read(Path(a['path'])/'validation.json')
   self.assertTrue(validation['passed']);self.assertTrue(validation['navigation']['passed']);self.assertFalse(validation['warnings']);self.assertEqual(meta['id'],ident)
   if ident.endswith('v02'):self.assertEqual(meta['connections'],[])

class DesignedLandscapeIntegration(unittest.TestCase):
 def test_actual_voxels_and_greenery_toggle_preserve_circulation(self):
  old_output=api.OUTPUT
  with tempfile.TemporaryDirectory() as t:
   api.OUTPUT=Path(t)
   try:
    meta=api.build(api.read(Path(__file__).with_name('campus-groups.json')));folder=Path(meta['evidenceDirectory']);r=api.read(folder/'java-result.json');world=api.read(folder/'world.json')
    self.assertEqual(len(meta['members']),23);self.assertEqual(len(meta['terraces']),4)
    # Shared-platform boundaries are now eliminated by the production Java compiler/executor.
    self.assertEqual(len(meta['removedBuildingEnclosures']),0)
    # Explicit core terrace edge features survive the internal lot cleanup.
    self.assertTrue(any(w[4].startswith('TERRACE_EDGE_') for w in r['mutations']))
    self.assertTrue(all(o['status'].startswith('CONNECTED') for o in r['streetReview']['accessOutcomes']))
    self.assertEqual(len(meta['fixtureChecks']),len(meta['fixtures']))
    self.assertTrue(all(c['supported'] and c['buildingClearance'] and c['roadClearance'] for c in meta['fixtureChecks']))
    plans=meta['publicSpacePlans'];self.assertEqual(len(plans),4)
    for p in plans[:2]:
     self.assertEqual(p['supportedColumns'],p['bounds']['width']*p['bounds']['depth']);self.assertEqual(len(p['beds']),4)
     self.assertEqual(p['counts'].get('FLOWER',0),0);self.assertEqual(len(p['trees']),0)
     cx=p['bounds']['x']+p['bounds']['width']//2;cz=p['bounds']['z']+p['bounds']['depth']//2
     for x,y,z,kind in p['writes']:
      if kind in ('LOW_HEDGE','FLOWER','TREE_TRUNK','TREE_CROWN'):
       self.assertGreater(abs(x-cx),2);self.assertGreater(abs(z-cz),2)
    self.assertTrue(any(a['kind']=='WELL' for a in plans[1]['amenities']))
    gaps=plans[-1];self.assertEqual(gaps['kind'],'GAP_LANDSCAPE');self.assertGreater(len(gaps['regions']),0)
    self.assertEqual(gaps['counts'].get('TREE_TRUNK',0),0)
    placements=meta['greeneryTemplatePlan']['placements'];self.assertTrue(placements)
    planting={p['nodeId']:{(x,z) for x,y,z,k in p['writes'] if k in ('BED','LAWN')} for p in plans}
    for a in placements:
     self.assertTrue(a['nbtSha256']);self.assertTrue(a['authorSha256'])
     w,_,d=a['sizeXYZ']
     if a['rotation']%180:w,d=d,w
     o=a['origin'];self.assertTrue({(x,z) for x in range(o['x'],o['x']+w) for z in range(o['z'],o['z']+d)}<=planting[a['nodeId']])
    native={(c[0],c[1]):(c[2],c[3]) for c in r['terrainColumns']}
    footprint={(x,row['z']) for t in meta['terraces'] for row in t['spans'] for x in range(row['minX'],row['maxX']+1)}
    engineering={(c['x'],c['z']):c for c in gaps['engineeringColumns']}
    self.assertEqual(gaps['terrainPolicy'],'CONNECTION_TERRACE');self.assertGreater(len(engineering),0)
    connection=meta['connectionTerracePlan'];self.assertGreater(len(connection['removedInternalEdges']),0)
    self.assertGreater(len(connection['synchronizedColumns']),0)
    self.assertGreater(len(connection['outerRailColumns']),0)
    self.assertTrue(any(w[3]=='SUPPORT' for w in connection['writes']))
    road_y={(f['x'],f['z']):f['targetSurfaceY'] for f in r['surfacePrintPlan']['featureCells'] if f['surfaceOffset']==0}
    for p,c in engineering.items():
     self.assertNotIn(p,footprint);self.assertNotIn(p,road_y)
     self.assertEqual(c['targetY'],road_y[(c['roadX'],c['roadZ'])])
    for x,y,z,kind in gaps['writes']:
     self.assertNotIn((x,z),footprint);self.assertNotEqual(native[(x,z)][1],'minecraft:water')
     if kind=='LAWN':self.assertEqual(y,engineering[(x,z)]['targetY'])
     for band in r['roadBands']:
      b=band['bounds']
      if kind not in ('CLEAR','FILL','SURFACE'):self.assertFalse(b['minX']-2<=x<=b['maxX']+2 and b['minZ']-2<=z<=b['maxZ']+2)
    registry=api.read(api.TOOL/'.cache/registry.json')
    from studio.navigation import collision_boxes
    for palette in world['palette']:collision_boxes(palette,registry)
    for p in plans[:2]+[gaps]:
     self.assertGreater(len(p['borderPlacements']),0)
     for b in p['borderPlacements']:
      self.assertTrue(b['nbtSha256']);self.assertIn(b['assetId'],r['scene']['landscapeBorder'].values())
    before={p['nodeId']:{k:v for k,v in p['counts'].items() if k not in ('BED','LOW_HEDGE','FLOWER','TREE_TRUNK','TREE_CROWN','BORDER')} for p in plans[:2]}
    r['scene']['greenery']=False;r['_groundIndex']={(row[0],row[1]):row[2] for row in r['terrainColumns']};api.compose(r,api.catalog())
    after={p['nodeId']:p['counts'] for p in r['publicSpacePlans'][:2]};self.assertEqual(before,after)
    self.assertFalse(r['greeneryTemplatePlan']['enabled']);self.assertEqual(r['greeneryTemplatePlan']['placements'],[])
    self.assertEqual(r['publicSpacePlans'][-1]['engineeringColumns'],gaps['engineeringColumns'])
    self.assertEqual(api.read(api.OUTPUT/'state.json')['revision'],1)
   finally:api.OUTPUT=old_output

if __name__=='__main__':unittest.main()
