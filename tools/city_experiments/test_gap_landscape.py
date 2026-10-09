import unittest
from gap_landscape import plan_gap_regions,road_level_targets


def fixture(separation=16):
    terraces=[]
    for ident,x0 in (('a',0),('b',separation)):
        terraces.append(dict(terraceId=ident,datumY=64,spans=[dict(z=z,minX=x0,maxX=x0+2,targetY=64) for z in range(30)]))
    return dict(terraces=terraces,roadBands=[],fixtures=[],terrainColumns=[[x,z,64,'minecraft:grass_block'] for x in range(separation+3) for z in range(30)])


class GapLandscape(unittest.TestCase):
    def test_configured_boundary_distance_includes_road_width(self):
        for separation,expected in ((34,True),(35,False)):
            r=fixture(separation);r['scene']={'gapSurface':'CONNECTION_TERRACE','smallGapMaxBlocks':32}
            regions,*_=plan_gap_regions(r,road_level=True)
            self.assertEqual(bool(regions),expected)
    def test_road_grade_extends_to_both_sides_of_gap(self):
        r=fixture();r['surfacePrintPlan']={'featureCells':[dict(x=9,z=z,targetSurfaceY=68,kind='ROAD_SLAB',surfaceOffset=0) for z in range(30)]}
        regions,ground,_,_=plan_gap_regions(r,road_level=True)
        targets=road_level_targets(r,regions,ground)
        self.assertEqual(targets[(8,15)][0],68);self.assertEqual(targets[(10,15)][0],68)
        self.assertTrue(all(row[0]==68 for row in targets.values()))
        self.assertNotIn((9,15),targets)

    def test_road_grade_follows_steps_without_filling_water(self):
        r=fixture();r['surfacePrintPlan']={'featureCells':[dict(x=9,z=z,targetSurfaceY=64+z//10,kind='ROAD_SLAB',surfaceOffset=0) for z in range(30)]}
        for row in r['terrainColumns']:
            if row[1]<3:row[3]='minecraft:water'
        regions,ground,_,_=plan_gap_regions(r,road_level=True);targets=road_level_targets(r,regions,ground)
        for z in (6,15,25):self.assertEqual(targets[(8,z)][0],64+z//10)
        self.assertTrue(all(ground[p][1]!='minecraft:water' for p in targets))

    def test_small_gap_connects_actual_boundaries(self):
        regions,ground,roads,occupied=plan_gap_regions(fixture())
        self.assertGreater(len(regions),0)
        for region in regions:
            self.assertEqual(region['pair'],('a','b'))
            self.assertTrue(region['columns'].isdisjoint(occupied))

    def test_water_and_deep_valley_interrupt_gap_detection(self):
        for water in (True,False):
            result=fixture()
            for row in result['terrainColumns']:
                if row[0]==9:
                    row[2]=64 if water else 30
                    row[3]='minecraft:water' if water else 'minecraft:stone'
            self.assertEqual(plan_gap_regions(result)[0],[])

    def test_distant_cores_do_not_turn_into_a_giant_garden(self):
        self.assertEqual(plan_gap_regions(fixture(60))[0],[])


if __name__=='__main__':unittest.main()
