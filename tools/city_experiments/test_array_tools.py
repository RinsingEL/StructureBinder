"""Integration checks using the real Gradle/Java adapter and frozen D3 data."""
import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
import array_tools as api


class ArrayIntegration(unittest.TestCase):
    def test_preview_update_and_guards(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            scene = api.read(api.DEFAULT/'工具示例.json')
            first = api.invoke('layout_preview', scene, api.DEFAULT, output)
            self.assertEqual(first['summary']['planned'], 25)
            self.assertEqual(first['summary']['conflicts'], 3)
            core = first['members'][0]
            self.assertEqual(core['originBlock'], scene['arrays'][0]['anchorBlock'])
            self.assertFalse(core['placeholder'])
            self.assertEqual(core['geometryEngine'], 'CityBlueprintGroupLayoutPlanner.propose')
            collided = {m['memberId']:m for m in first['members'] if m['collisionsWith']}
            for mid, member in collided.items():
                for other in member['collisionsWith']:
                    self.assertIn(mid, collided[other]['collisionsWith'])
            plots = [m for m in first['members'] if m['arrayId']=='planting']
            self.assertTrue(all(m['geometryEngine']=='CityContiguousLayoutPlanner.plan' for m in plots))
            positions = {(m['originBlock']['x'],m['originBlock']['z']) for m in plots}
            self.assertEqual(len(positions), 6)
            connected = {next(iter(positions))}
            while True:
                added = {p for p in positions-connected if any(abs(p[0]-q[0])+abs(p[1]-q[1])==17 for q in connected)}
                if not added: break
                connected |= added
            self.assertEqual(connected, positions)
            self.assertTrue(all('WATER_INTERFACE_REVIEW' in m['engineeringNeeds'] for m in first['members'] if m['arrayId']=='dock'))
            edited = copy.deepcopy(next(a for a in scene['arrays'] if a['arrayId']=='reception'))
            edited['anchorBlock']['z'] = 5240
            second = api.invoke('layout_update', {'baseRevision':1,'arrays':[edited]}, api.DEFAULT, output)
            self.assertEqual(second['revision'], 2)
            self.assertEqual(second['summary']['conflicts'], 0)
            old = {m['memberId']:m for m in first['members']}
            for member in second['members']:
                if member['arrayId']!='reception':
                    self.assertEqual(member['originBlock'], old[member['memberId']]['originBlock'])
            self.assertEqual({m['assetId'] for m in second['members'] if m['placeholder']}, {m['assetId'] for m in second['missingAssets']})
            self.assertTrue(Path(second['previews']['focus']).is_file())
            with self.assertRaisesRegex(ValueError, 'Stale'):
                api.invoke('layout_update', {'baseRevision':1}, api.DEFAULT, output)
            state = api.read(output/'state.json'); state['inputFingerprint']='changed'; api.write(output/'state.json',state)
            with self.assertRaisesRegex(ValueError, 'inputs changed'):
                api.invoke('layout_update', {'baseRevision':2}, api.DEFAULT, output)
            bad = copy.deepcopy(scene); bad['arrays'][0]['gapBlocks']=4
            with self.assertRaisesRegex(ValueError, 'unknown field'):
                api.invoke('layout_preview', bad, api.DEFAULT, output)

    def test_nested_rotation_and_outside(self):
        array = {'arrayId':'a','anchorBlock':{'x':-3000,'z':5250},'algorithm':'LINEAR','count':1,'rotation':90,'coreAssetId':'mock:classroom_small'}
        outside = {'arrayId':'outside','anchorBlock':{'x':0,'z':0},'algorithm':'GRID','count':1,'coreAssetId':'mock:dorm_small'}
        compositions = [
            {'compositionId':'inner','anchorBlock':{'x':-2900,'z':5250},'algorithm':'GRID','memberIds':['a']},
            {'compositionId':'outer','anchorBlock':{'x':-2800,'z':5250},'algorithm':'GRID','memberIds':['inner']}]
        with tempfile.TemporaryDirectory() as temp:
            output=Path(temp)
            result=api.invoke('layout_preview', {'arrays':[array,outside],'compositions':compositions},api.DEFAULT,output)
            nested=next(m for m in result['members'] if m['arrayId']=='a')
            self.assertEqual((nested['width'],nested['depth']), (17,21))
            missing=next(m for m in result['missingAssets'] if m['assetId']=='mock:classroom_small')
            self.assertEqual((missing['size']['width'],missing['size']['depth']), (21,17))
            self.assertEqual(nested['compositionPath'], ['inner','outer'])
            self.assertGreaterEqual(nested['originBlock']['x'], -2800)
            self.assertIn('OUTSIDE_D3',next(m for m in result['members'] if m['arrayId']=='outside')['issues'])
            compositions[0]['memberIds']=['outer']
            with self.assertRaisesRegex(ValueError, 'cycle'):
                api.invoke('layout_preview',{'arrays':[array],'compositions':compositions},api.DEFAULT,output)

    def test_stdio_transport(self):
        request={'array':{'arrayId':'probe','anchorBlock':{'x':-2900,'z':5250},'algorithm':'GRID','count':1,'coreAssetId':'mock:classroom_small'}}
        messages=[{'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2024-11-05'}}, {'jsonrpc':'2.0','method':'notifications/initialized'}, {'jsonrpc':'2.0','id':2,'method':'tools/list'}, {'jsonrpc':'2.0','id':3,'method':'tools/call','params':{'name':'array_preview','arguments':request}}]
        with tempfile.TemporaryDirectory() as temp:
            proc=subprocess.run([sys.executable,str(Path(api.__file__)), '--stdio','--output',temp],input='\n'.join(json.dumps(m) for m in messages)+'\n',capture_output=True,text=True,check=True)
            replies=[json.loads(line) for line in proc.stdout.splitlines()]
            self.assertEqual([r['id'] for r in replies],[1,2,3])
            self.assertEqual(len(replies[1]['result']['tools']),3)
            call=replies[2]['result']; self.assertFalse(call['isError'])
            self.assertEqual([c['type'] for c in call['content']],['text','image','image'])
            self.assertIsNone(json.loads(call['content'][0]['text'])['revision'])
            self.assertFalse((Path(temp)/'state.json').exists())


if __name__=='__main__': unittest.main(verbosity=2)
