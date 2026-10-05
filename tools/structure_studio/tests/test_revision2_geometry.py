"""Regression cases from the second pass's structural and walking review."""
import json
from pathlib import Path
import tempfile
import unittest
from studio.model import Model
from studio.revision_geometry import arch
from studio.european_revision2 import courtyard_inn
from studio.elven_revision2 import inn
from studio.server import TOOL
from studio.validate import validate


class ArchContinuityTests(unittest.TestCase):
    def test_steep_arch_is_one_face_connected_structure(self):
        for kind in ('pointed','round','angular'):
            with self.subTest(kind=kind):
                m=Model('TEST','steep arch',(23,36,5))
                arch(m,11,2,1,15,5,23,'stone_bricks',kind=kind)
                stone={p for p,(name,_) in m.blocks.items() if name=='minecraft:stone_bricks'}
                remaining=set(stone);stack=[remaining.pop()]
                while stack:
                    x,y,z=stack.pop()
                    for dx,dy,dz in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)):
                        q=(x+dx,y+dy,z+dz)
                        if q in remaining:remaining.remove(q);stack.append(q)
                self.assertFalse(remaining, 'Disconnected voxels in an authored load-bearing arch')
                self.assertEqual(m.blocks[(11,2,2)][0],'minecraft:air')


class CurrentAccessTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.registry=json.loads((TOOL/'.cache/registry.json').read_text())

    def report(self,m,path):
        m.export(path,self.registry)
        return validate(path,self.registry)['navigation']

    def test_three_storey_courtyard_inn_needs_upper_flight(self):
        m=courtyard_inn()
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder);good=self.report(m,p)
            self.assertTrue(good['passed'],good)
            m.box((19,9,32),(21,15,38),'air')
            bad=self.report(m,p)
            self.assertIn('gallery_16',bad['unreachable'])
            self.assertNotIn('gallery_9',bad['unreachable'])

    def test_elven_high_guest_pods_need_actual_stairs(self):
        m=inn()
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder);good=self.report(m,p)
            self.assertTrue(good['passed'],good)
            for cx in (17,53):m.box((cx-1,2,26),(cx+1,9,33),'air')
            bad=self.report(m,p)
            self.assertIn('bridge',bad['unreachable'])
            self.assertNotIn('lobby',bad['unreachable'])


if __name__=='__main__':unittest.main()
