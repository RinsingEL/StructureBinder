import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'structure_studio'))
from template_greenery import placement,rotated,load_catalog,apply_template_greenery


class TemplatePlacement(unittest.TestCase):
    def setUp(self):
        self.cells={(x,0,z):{'name':'minecraft:grass_block'} for x in range(-6,7) for z in range(-6,7)}
        self.sources={p:'landscape:test' for p in self.cells}
        self.allowed={(x,z) for x in range(-6,7) for z in range(-6,7)}
        self.entry=dict(anchorXYZ=[1,0,1],groundPlaneY=0)
        self.model=dict(size=[3,4,3],palette=[dict(name='minecraft:birch_log',properties={})],blocks=[dict(pos=[1,y,1],state=0) for y in range(4)])

    def attempt(self,protected=set()):
        return placement(self.entry,self.model,0,0,0,0,self.cells,self.sources,self.allowed,protected,lambda s,r:s)

    def test_supported_template_keeps_root_at_surface(self):
        writes,origin,_=self.attempt()
        self.assertEqual(origin,dict(x=-1,y=1,z=-1))
        self.assertEqual(min(p[1] for p in writes),1)

    def test_canopy_box_avoids_road_even_if_trunk_does_not_touch_it(self):
        self.assertIsNone(self.attempt({(1,1)}))

    def test_unwritten_template_space_does_not_clear_existing_block(self):
        self.cells[(1,3,1)]={'name':'minecraft:stone'}
        self.assertIsNone(self.attempt())
        self.assertEqual(self.cells[(1,3,1)]['name'],'minecraft:stone')

    def test_missing_ground_rejects_root(self):
        del self.cells[(0,0,0)]
        self.assertIsNone(self.attempt())

    def test_low_plant_can_fit_below_high_canopy_but_not_through_trunk(self):
        self.model['size']=[3,2,3]
        self.model['blocks']=[dict(pos=[1,0,1],state=0)]
        self.cells[(0,8,0)]={'name':'minecraft:birch_leaves'}
        self.assertIsNotNone(self.attempt())
        self.cells[(0,1,0)]={'name':'minecraft:birch_log'}
        self.assertIsNone(self.attempt())

    def test_embedded_soil_replaces_only_planting_surface(self):
        self.cells[(0,-1,0)]={'name':'minecraft:dirt'}
        self.entry['groundPlaneY']=1
        self.model['palette']=[dict(name='minecraft:dirt',properties={})]
        self.model['blocks']=[dict(pos=[1,0,1],state=0)]
        self.assertIsNotNone(self.attempt())
        self.sources[(0,0,0)]='building:test'
        self.assertIsNone(self.attempt())

    def test_rectangular_rotation_preserves_anchor_extent(self):
        for rotation in (0,90,180,270):
            points={rotated(x,z,5,3,rotation) for x in range(5) for z in range(3)}
            self.assertEqual(len(points),15)
            self.assertEqual(max(p[0] for p in points)+1,3 if rotation%180 else 5)

    def test_catalog_cannot_escape_asset_root(self):
        with self.assertRaises(ValueError):load_catalog({'catalog':'../../greenery-pools.json','pools':{}},[])

    def test_real_pool_selection_is_repeatable_and_respects_disabled_greenery(self):
        from workbench import catalog,rotate_state
        assets=catalog();config={'catalog':'R02_arcane_reborn/greenery-pools.json','pools':{'trees':'courtyard_trees'},'maximumPlacements':8}
        data=load_catalog(config,assets)
        def run(seed,green=True):
            cells={(x,y,z):{'name':'minecraft:grass_block' if y==0 else 'minecraft:dirt','properties':{}}
                   for x in range(32) for z in range(32) for y in (-1,0)}
            sources={p:'landscape:test' for p in cells}
            result={'scene':{'seed':seed,'greenery':green,'greeneryTemplates':config,'greeneryCatalog':data},
                    'members':[],'roadBands':[],'surfacePrintPlan':{'featureCells':[]},
                    'publicSpacePlans':[{'nodeId':'garden','kind':'GARDEN','writes':[[x,0,z,'BED'] for x in range(32) for z in range(32)]}]}
            def put(x,y,z,state,layer):cells[(x,y,z)]=state;sources[(x,y,z)]=layer
            return apply_template_greenery(result,cells,sources,put,assets,rotate_state)['placements']
        a=run(17);self.assertTrue(a);self.assertEqual(a,run(17));self.assertNotEqual(a,run(18));self.assertEqual(run(17,False),[])


if __name__=='__main__':unittest.main()
