"""Pitched overhangs must meet their walls at the wall line, not the roof edge."""
import unittest
from studio.model import Model
from studio.european_reborn_parts import shell,gable
from studio.arcane_reborn_parts import rect,gable as academy_gable
from studio.elven_reborn_parts import pavilion

class RoofBearingTests(unittest.TestCase):
    def assert_bearing(self,m,x,z,wall_top,roof_names):
        roof_y=next(y for y in range(wall_top+1,m.size[1]) if m.blocks.get((x,y,z),('minecraft:air',))[0] in roof_names)
        for y in range(wall_top,roof_y+1):
            self.assertNotEqual(m.blocks.get((x,y,z),('minecraft:air',))[0],'minecraft:air',(x,y,z))

    def test_european_overhanging_roof_has_continuous_wall_plates(self):
        m=Model('TEST','roof',(31,35,37));shell(m,(6,8,24,31),height=7);gable(m,(6,8,24,31),9)
        for x in (6,24):self.assert_bearing(m,x,19,8,{'minecraft:brick_stairs'})

    def test_academy_both_ridge_directions_meet_wall_top(self):
        for axis in ('x','z'):
            m=Model('TEST','roof',(31,35,37));rect(m,6,8,24,31,top=10);academy_gable(m,6,8,24,31,11,axis=axis)
            for x,z in ([(6,19),(24,19)] if axis=='z' else [(15,8),(15,31)]):
                self.assert_bearing(m,x,z,10,{'minecraft:purple_terracotta'})

    def test_elven_cut_corner_roof_follows_wall_ring(self):
        m=Model('TEST','roof',(41,45,43));pavilion(m,'room','room',(7,9,33,35),floor=2,height=10,roof_height=18,cut=5)
        for x,z in ((7,22),(33,22),(20,9),(20,35),(9,12)):
            self.assert_bearing(m,x,z,12,{'minecraft:dark_prismarine','minecraft:waxed_oxidized_cut_copper'})

if __name__=='__main__':unittest.main()
