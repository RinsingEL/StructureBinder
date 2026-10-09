"""Regression checks against actual Java output, NBT placement and atomic scene versions."""
import copy, json, tempfile, unittest, runpy
from pathlib import Path
import workbench as api


def connected(spans):
    remaining={(x,s['z']) for s in spans for x in range(s['minX'],s['maxX']+1)}
    queue=[remaining.pop()]
    while queue:
        x,z=queue.pop()
        for p in ((x+1,z),(x-1,z),(x,z+1),(x,z-1)):
            if p in remaining:remaining.remove(p);queue.append(p)
    return not remaining


class CampusIntegration(unittest.TestCase):
    def test_legacy_core_facing_is_an_actual_road_entrance(self):
        core=next(a for a in api.catalog() if a['id']=='MG-01-v01')
        self.assertEqual(core['entrances'],[dict(id='street',x=35,z=0,direction='NORTH')])

    def test_real_academy_and_failed_rebuild_preserve_scene(self):
        old_output=api.OUTPUT
        with tempfile.TemporaryDirectory() as temp:
            api.OUTPUT=Path(temp)
            try:
                scene=api.read(api.SCENE)
                # Explicit core ownership must work independently of child ordering.
                scene['compositions'][1]['memberIds'].reverse()
                meta=api.build(scene);folder=Path(meta['evidenceDirectory'])
                result=api.read(folder/'java-result.json');world=api.read(folder/'world.json')
                self.assertEqual(meta['summary']['buildings'],17)
                self.assertEqual(len(meta['terraces']),2)
                self.assertGreater(meta['summary']['lamps'],0)
                self.assertGreater(meta['summary']['wallBlocks'],0)
                self.assertFalse(meta['generationReady'])
                self.assertTrue(all(r['status']=='APPLIED' for r in result['execution']))
                self.assertEqual(len(result['roadPlan']['connections']),2)
                self.assertEqual(result['roadPlan']['skippedConnections'],[])
                self.assertTrue(all(o['status'].startswith('CONNECTED') for o in result['streetReview']['accessOutcomes']))
                for terrace in meta['terraces']:
                    self.assertTrue(connected(terrace['spans']))
                    heights={s['targetY'] for s in terrace['spans']}
                    self.assertEqual(len(heights),1)
                    members=[m for m in meta['members'] if m['districtId']==terrace['districtId']]
                    self.assertEqual({m['surfaceY'] for m in members},heights)
                core=next(m for m in meta['members'] if m['memberId']=='teaching-core:1')
                self.assertEqual(core['originBlock'],scene['compositions'][1]['anchorBlock'])
                nested=next(m for m in meta['members'] if m['memberId']=='teaching-small:1')
                self.assertEqual(nested['compositionPath'],['teaching-support','teaching-campus'])
                self.assertTrue(all(m['nbtSha256'] for m in meta['members']))
                self.assertTrue(any(b['layer']=='infrastructure' for b in world['blocks']))
                self.assertTrue(any(b['layer']=='building:teaching-small:1' for b in world['blocks']))
                self.assertNotIn('agroforestry',[t['districtId'] for t in meta['terraces']])
                before=(api.OUTPUT/'state.json').read_bytes()
                with self.assertRaises(api.RevisionConflict):api.build(scene,0)
                bad=copy.deepcopy(scene);bad['arrays'][0]['coreAssetId']='studio:mg-09-v01'
                with self.assertRaisesRegex(ValueError,'基础设施'):api.build(bad,1)
                bad=copy.deepcopy(scene);bad['arrays'][4]['anchorBlock']={'x':-3000,'z':5370}
                with self.assertRaisesRegex(ValueError,'collision, bounds or water'):api.build(bad,1)
                self.assertEqual((api.OUTPUT/'state.json').read_bytes(),before)
            finally:api.OUTPUT=old_output

    def test_schema_limits_and_rotation_keep_connected_block_properties(self):
        scene=api.read(api.SCENE);assets=api.catalog()
        for change in ('boolean','duplicate','consumer','unknown','oversized'):
            bad=copy.deepcopy(scene)
            if change=='boolean':bad['districts'][0]['platform']='true'
            elif change=='duplicate':bad['districts'][1]['districtId']='teaching'
            elif change=='consumer':bad['arrays'][0]['coreAssetId']='studio:mg-24-v01'
            elif change=='unknown':bad['arrays'][0]['gapBlocks']=2
            else:bad['arrays'][3]['count']=181
            with self.assertRaises(ValueError,msg=change):api.validate_scene(bad,assets)
        original={'name':'minecraft:stone_brick_wall','properties':{'north':'low','east':'tall','south':'none','west':'none','up':'true','waterlogged':'false'}}
        rotated=api.rotate_state(original,90)
        self.assertEqual(rotated['properties']['east'],'low')
        self.assertEqual(rotated['properties']['south'],'tall')
        self.assertEqual(original['properties']['north'],'low')


class TerraceIntegration(unittest.TestCase):
    def test_same_planner_builds_two_themes_and_independent_core_platforms(self):
        old_output=api.OUTPUT
        with tempfile.TemporaryDirectory() as temp:
            try:
                for name in ('terrace-trial.json','town-terrace-trial.json'):
                    api.OUTPUT=Path(temp)/name
                    scene=api.read(Path(api.__file__).with_name(name));meta=api.build(scene)
                    raw=api.read(Path(meta['evidenceDirectory'])/'java-result.json')
                    self.assertTrue(meta['fixtures']);self.assertGreater(meta['summary']['terraceFeatureBlocks'],0)
                    self.assertEqual(len(meta['fixtureChecks']),len(meta['fixtures']))
                    self.assertTrue(all(c['supported'] and c['roadClearance'] and c['buildingClearance'] for c in meta['fixtureChecks']))
                    self.assertTrue(all(r['status']=='APPLIED' for r in raw['execution']))
                    self.assertTrue(all(o['status'].startswith('CONNECTED') for o in raw['streetReview']['accessOutcomes']))
                    all_cells=set()
                    for terrace in meta['terraces']:
                        owned={(x,s['z']) for s in terrace['spans'] for x in range(s['minX'],s['maxX']+1)}
                        self.assertFalse(owned&all_cells);all_cells.update(owned)
                        self.assertEqual(len({s['targetY'] for s in terrace['spans']}),1)
                        members=[m for m in meta['members'] if m.get('terraceId')==terrace['terraceId']]
                        self.assertEqual({m['surfaceY'] for m in members},{terrace['datumY']})
                        for f in [f for f in meta['fixtures'] if f['terraceId']==terrace['terraceId']]:
                            ox,oz=f['originBlock']['x'],f['originBlock']['z']
                            self.assertTrue({(x,z) for x in range(ox,ox+f['width']) for z in range(oz,oz+f['depth'])}<=owned)
                    if name.startswith('town'):
                        self.assertTrue(all(m['assetId'].startswith('studio:eu-') for m in meta['members']))
                        self.assertTrue(all(f['assetId'].startswith('studio:eu-') for f in meta['fixtures']))
                    else:
                        teacher=[t for t in meta['terraces'] if t['districtId']=='teaching']
                        self.assertEqual(len(teacher),2);self.assertEqual({t['datumY'] for t in teacher},{72,76})
                        # Traverse emitted collision shapes from only one entrance, across the four-block rise.
                        walk=runpy.run_path(str(api.ROOT/'tools/structure_studio/scripts/check-terrace-walk.py'))['check']
                        self.assertTrue(walk(Path(meta['evidenceDirectory'])))
                        self.assertEqual(len({b['streetBandId'] for b in raw['roadBands']}),len(raw['roadBands']))
                        world=api.read(Path(meta['evidenceDirectory'])/'world.json')
                        for b in world['blocks']:
                            if b['layer']=='infrastructure' and world['palette'][b['state']]['name']=='minecraft:polished_andesite_slab':
                                self.assertEqual(world['palette'][b['state']]['properties']['type'],'double')
                        before=(api.OUTPUT/'state.json').read_bytes();bad=copy.deepcopy(scene)
                        bad['terraceGroups'][1]['arrayIds'].append('teaching-core')
                        with self.assertRaisesRegex(ValueError,'exclusive ownership'):api.build(bad,1)
                        self.assertEqual((api.OUTPUT/'state.json').read_bytes(),before)
            finally:api.OUTPUT=old_output

    def test_facilities_can_be_disabled_and_buildings_cannot_be_used_as_fixtures(self):
        scene=api.read(Path(api.__file__).with_name('terrace-trial.json'))
        bad=copy.deepcopy(scene);bad['terraceStyles'][0]['cornerAssetId']='studio:mg-01-v01'
        with self.assertRaisesRegex(ValueError,'程序消费'):api.validate_scene(bad,api.catalog())
        old_output=api.OUTPUT
        with tempfile.TemporaryDirectory() as temp:
            try:
                api.OUTPUT=Path(temp);scene['terraceFeatures']=False;meta=api.build(scene)
                self.assertEqual(meta['fixtures'],[]);self.assertEqual(meta['summary']['terraceFeatureBlocks'],0)
                self.assertEqual(len(meta['terraces']),3)
            finally:api.OUTPUT=old_output


class TerrainContext(unittest.TestCase):
    def test_full_frozen_d3_preserves_water_and_detailed_window(self):
        with tempfile.TemporaryDirectory() as temp:
            result=Path(temp)/'java-result.json'
            api.write(result,{'terrainColumns':[[-3024,5117,65,'minecraft:grass_block'],[-2774,5464,63,'minecraft:grass_block']]})
            before=result.read_bytes();context=api.terrain_context(temp)
            source=api.read(api.EXPERIMENT/'data/D3地形原始数据.json')
            self.assertEqual(context['bounds'],source['planningBounds'])
            self.assertEqual(len(context['samples']),4096)
            self.assertEqual(context['waterSampleCount'],2214)
            self.assertEqual(context['waterSampleCount'],sum(s[3] for s in context['samples']))
            self.assertEqual(context['detailedBounds'],dict(minX=-3024,maxX=-2774,minZ=5117,maxZ=5464))
            self.assertEqual(context['waterDisplayMode'],'INFERRED_DISPLAY_LEVEL')
            self.assertEqual(result.read_bytes(),before)
        api.terrain_context.cache_clear()


class WriteOrigins(unittest.TestCase):
    def test_explicit_tailnet_origin_requires_matching_host(self):
        old_output=api.OUTPUT
        with tempfile.TemporaryDirectory() as temp:
            api.OUTPUT=Path(temp)
            try:
                local={'Host':'127.0.0.1:8767','Origin':'http://127.0.0.1:8767'}
                remote={'Host':'device.example.ts.net:8767','Origin':'http://device.example.ts.net:8767'}
                self.assertTrue(api.allows_write_origin(local,8767))
                self.assertFalse(api.allows_write_origin(remote,8767))
                api.write(api.OUTPUT/'access.json',{'allowedOrigins':[remote['Origin']]})
                self.assertTrue(api.allows_write_origin(remote,8767))
                self.assertFalse(api.allows_write_origin({**remote,'Origin':'https://other.example'},8767))
                self.assertFalse(api.allows_write_origin({**remote,'Host':local['Host']},8767))
                self.assertFalse(api.allows_write_origin({'Host':remote['Host']},8767))
                (api.OUTPUT/'access.json').write_text('{',encoding='utf-8')
                self.assertFalse(api.allows_write_origin(remote,8767))
            finally:api.OUTPUT=old_output


if __name__=='__main__':unittest.main(verbosity=2)
